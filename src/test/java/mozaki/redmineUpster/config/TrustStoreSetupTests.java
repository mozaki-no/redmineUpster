package mozaki.redmineUpster.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrustStoreSetupTests {

	private static X509TrustManager manager(boolean trusts) {
		return new X509TrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				checkServerTrusted(chain, authType);
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				if (!trusts) {
					throw new CertificateException("PKIX path building failed");
				}
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return new X509Certificate[0];
			}
		};
	}

	@Test
	@DisplayName("Java の CA か Windows の証明書ストアのどちらかで検証できれば信頼する")
	void compositeTrustsIfAnyTrusts() {
		var composite = new TrustStoreSetup.CompositeTrustManager(List.of(manager(false), manager(true)));
		assertThatCode(() -> composite.checkServerTrusted(new X509Certificate[0], "RSA")).doesNotThrowAnyException();
		var none = new TrustStoreSetup.CompositeTrustManager(List.of(manager(false), manager(false)));
		assertThatThrownBy(() -> none.checkServerTrusted(new X509Certificate[0], "RSA"))
				.hasMessageContaining("PKIX");
	}

	@Test
	@DisplayName("Windows 以外では何もしない")
	void noopOutsideWindows() throws Exception {
		TrustStoreSetup.configure();
		TrustStoreSetup.trustManager(null);
	}
}
