package mozaki.redmineUpster.config;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS の証明書の検証に Windows の証明書ストアも使う設定。
 * <p>
 * 社内の Redmine は社内CAの証明書を使うことが多く、その CA は Windows には登録されていても
 * 同梱の Java（cacerts）にはないため「PKIX path building failed」になります。
 * Windows では Java 同梱の CA と Windows の「信頼されたルート証明機関」（Windows-ROOT）の
 * どちらかで検証できれば信頼します。{@code javax.net.ssl.trustStore} / {@code trustStoreType} を
 * 指定して起動した場合はその指定に従い、何もしません。
 * </p>
 */
public final class TrustStoreSetup {

	private TrustStoreSetup() {
	}

	/**
	 * Windows なら既定の SSLContext を「Java の CA ＋ Windows の証明書ストア」にします。
	 */
	public static void configure() {
		String os = System.getProperty("os.name", "");
		if (!os.toLowerCase().startsWith("windows") || System.getProperty("javax.net.ssl.trustStore") != null
				|| System.getProperty("javax.net.ssl.trustStoreType") != null) {
			return;
		}
		try {
			KeyStore windowsRoot = KeyStore.getInstance("Windows-ROOT");
			windowsRoot.load(null, null);
			List<X509TrustManager> managers = new ArrayList<>();
			managers.add(trustManager(null));
			managers.add(trustManager(windowsRoot));
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new TrustManager[] { new CompositeTrustManager(managers) }, null);
			SSLContext.setDefault(context);
		} catch (Exception e) {
			System.err.println("WARN: Windows の証明書ストアを使えませんでした（Java 同梱の証明書だけで検証します）: " + e);
		}
	}

	static X509TrustManager trustManager(KeyStore keyStore) throws GeneralSecurityException {
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init(keyStore);
		for (TrustManager manager : factory.getTrustManagers()) {
			if (manager instanceof X509TrustManager x509) {
				return x509;
			}
		}
		throw new GeneralSecurityException("X509TrustManager がありません");
	}

	/**
	 * いずれかの TrustManager で検証できれば信頼する TrustManager。
	 */
	static final class CompositeTrustManager implements X509TrustManager {
		private final List<X509TrustManager> managers;

		CompositeTrustManager(List<X509TrustManager> managers) {
			this.managers = managers;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			CertificateException last = null;
			for (X509TrustManager manager : managers) {
				try {
					manager.checkClientTrusted(chain, authType);
					return;
				} catch (CertificateException e) {
					last = e;
				}
			}
			throw last != null ? last : new CertificateException("信頼できる証明書がありません");
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			CertificateException last = null;
			for (X509TrustManager manager : managers) {
				try {
					manager.checkServerTrusted(chain, authType);
					return;
				} catch (CertificateException e) {
					last = e;
				}
			}
			throw last != null ? last : new CertificateException("信頼できる証明書がありません");
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			List<X509Certificate> issuers = new ArrayList<>();
			for (X509TrustManager manager : managers) {
				issuers.addAll(List.of(manager.getAcceptedIssuers()));
			}
			return issuers.toArray(new X509Certificate[0]);
		}
	}
}
