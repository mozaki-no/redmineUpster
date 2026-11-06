package mozaki.redmineUpster.service;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import mozaki.redmineUpster.config.ProxyConfig;

/**
 * 設定のプロキシを使う HttpURLConnection のリクエストファクトリ。
 * <p>
 * 接続先が {@code noProxyHosts} に一致すれば直接接続、それ以外は設定のプロキシを通します。
 * ユーザー名があれば、そのプロキシ（ホスト・ポートが一致する場合だけ）にだけ資格情報を返す
 * Authenticator を接続ごとに設定します（JVM 全体の Authenticator は変えません）。
 * </p>
 */
public class ProxyRequestFactory extends SimpleClientHttpRequestFactory {

	private final ProxyConfig config;
	private final Proxy proxy;
	private final Authenticator authenticator;
	private final String basicHeader;

	/**
	 * @param config プロキシ設定（host が設定済みであること）
	 */
	public ProxyRequestFactory(ProxyConfig config) {
		if (config == null || !config.isEnabled()) {
			throw new IllegalArgumentException("proxy host is required");
		}
		this.config = config;
		this.proxy = new Proxy(Proxy.Type.HTTP,
				InetSocketAddress.createUnresolved(config.getHost().trim(), config.effectivePort()));
		this.authenticator = config.hasCredentials() ? new ProxyAuthenticator(config) : null;
		// JDK の Basic 認証と同じ文字コード（ISO-8859-1）
		this.basicHeader = config.hasCredentials()
				? "Basic " + Base64.getEncoder().encodeToString((config.getUser().trim() + ":"
						+ (config.getPassword() == null ? "" : config.getPassword()))
						.getBytes(StandardCharsets.ISO_8859_1))
				: null;
		if (config.hasCredentials()) {
			ProxyConfig.allowBasicProxyAuthentication();
		}
	}

	@Override
	protected HttpURLConnection openConnection(URL url, Proxy ignored) throws IOException {
		boolean direct = config.bypasses(url.getHost());
		HttpURLConnection connection = (HttpURLConnection) url.openConnection(direct ? Proxy.NO_PROXY : proxy);
		if (!direct && authenticator != null) {
			connection.setAuthenticator(authenticator);
			if ("http".equalsIgnoreCase(url.getProtocol())) {
				// http:// の Redmine: 本文を送るリクエスト（固定長ストリーミング）は 407 のあと再送できないため、
				// 最初から Proxy-Authorization を付ける。https:// は CONNECT で認証し（ヘッダーはトンネル内の
				// Redmine に届いてしまうため付けない）、Authenticator が答える。
				connection.setRequestProperty("Proxy-Authorization", basicHeader);
			}
		}
		return connection;
	}

	/**
	 * @param host 接続先のホスト名
	 * @return その接続先にプロキシを使うなら true
	 */
	public boolean usesProxyFor(String host) {
		return !config.bypasses(host);
	}

	/**
	 * 設定のプロキシ（ホスト・ポートが一致）からの認証要求にだけ資格情報を返す Authenticator。
	 */
	static final class ProxyAuthenticator extends Authenticator {
		private final String host;
		private final int port;
		private final String user;
		private final char[] password;

		ProxyAuthenticator(ProxyConfig config) {
			this.host = config.getHost().trim().toLowerCase(Locale.ROOT);
			this.port = config.effectivePort();
			this.user = config.getUser().trim();
			this.password = config.getPassword() == null ? new char[0] : config.getPassword().toCharArray();
		}

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			if (getRequestorType() != RequestorType.PROXY) {
				return null;
			}
			String requestingHost = getRequestingHost();
			if (requestingHost == null || !host.equals(requestingHost.toLowerCase(Locale.ROOT))
					|| getRequestingPort() != port) {
				return null;
			}
			return new PasswordAuthentication(user, password.clone());
		}
	}
}
