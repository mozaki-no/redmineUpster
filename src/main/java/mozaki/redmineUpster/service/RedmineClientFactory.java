package mozaki.redmineUpster.service;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import mozaki.redmineUpster.config.ProxyConfig;
import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;

/**
 * Redmineクライアントファクトリ。
 * <p>
 * 各種設定からRedmineClientを生成します。プロキシ設定（redmine.proxy）ごとに RestTemplate を作ります。
 * </p>
 */
@Service
public class RedmineClientFactory {
	private final RestTemplateBuilder builder;
	private final RestTemplate directRestTemplate;
	private final RedmineProperties defaultProperties;
	private final Map<ProxyConfig, RestTemplate> proxyRestTemplates = new ConcurrentHashMap<>();

	/**
	 * RedmineClientFactoryを構築します。
	 *
	 * @param builder RestTemplateBuilder
	 * @param defaultProperties デフォルトのRedmine設定
	 */
	public RedmineClientFactory(RestTemplateBuilder builder, RedmineProperties defaultProperties) {
		this.builder = builder;
		// プロキシ設定なし: 従来どおり（JVM の -Dhttps.proxyHost などがあればそれに従う）
		this.directRestTemplate = build(new SimpleClientHttpRequestFactory());
		this.defaultProperties = defaultProperties;
		if (defaultProperties.getProxy() != null && defaultProperties.getProxy().hasCredentials()) {
			ProxyConfig.allowBasicProxyAuthentication();
		}
	}

	private RestTemplate build(ClientHttpRequestFactory factory) {
		// リクエストボディをバッファして Content-Length 付きで送る（chunked 送信を受け付けないサーバー・プロキシ対策）
		return builder.requestFactory(() -> new BufferingClientHttpRequestFactory(factory)).build();
	}

	/**
	 * ProjectConfigからクライアントを生成します。
	 * <p>
	 * CLI同期モードで使用されます。
	 * </p>
	 *
	 * @param projectConfig プロジェクト設定
	 * @return Redmineクライアント
	 */
	public RedmineClient createClient(ProjectConfig projectConfig) {
		if (projectConfig.getRedmine() == null) {
			throw new IllegalArgumentException("Redmine configuration is required in project config");
		}
		return create(projectConfig.getRedmine().getBaseUrl(), projectConfig.getRedmine().getApiKey(),
				projectConfig.getRedmine().getProjectId(), projectConfig.getRedmine().getProxy());
	}

	/**
	 * デフォルト設定からクライアントを生成します。
	 *
	 * @return Redmineクライアント
	 */
	public RedmineClient createDefaultClient() {
		return create(defaultProperties.getBaseUrl(), defaultProperties.getApiKey(),
				defaultProperties.getProjectId(), defaultProperties.getProxy());
	}

	private RedmineClient create(String baseUrl, String apiKey, String projectId, ProxyConfig proxy) {
		RestTemplate restTemplate = restTemplateFor(proxy);
		RedmineClient client = new RedmineClient(baseUrl, apiKey, projectId, restTemplate);
		client.setConnectionDescription(describe(proxy, baseUrl));
		return client;
	}

	/**
	 * @param proxy プロキシ設定（null・host 空欄なら従来どおり）
	 * @return その設定の RestTemplate
	 */
	RestTemplate restTemplateFor(ProxyConfig proxy) {
		if (proxy == null || !proxy.isEnabled()) {
			return directRestTemplate;
		}
		// 認証の設定（Basic の許可）は最初の接続より前に行う必要があるため、ここで（接続前に）作る
		return proxyRestTemplates.computeIfAbsent(proxy, p -> build(new ProxyRequestFactory(p)));
	}

	/**
	 * ログ用の接続方法の説明（パスワードは含まない）。
	 *
	 * @param proxy プロキシ設定
	 * @param baseUrl Redmine の URL
	 * @return 説明
	 */
	static String describe(ProxyConfig proxy, String baseUrl) {
		if (proxy == null || !proxy.isEnabled()) {
			String scheme = baseUrl != null && baseUrl.trim().toLowerCase().startsWith("http:") ? "http" : "https";
			String systemHost = System.getProperty(scheme + ".proxyHost");
			if (systemHost != null && !systemHost.isBlank()) {
				String port = System.getProperty(scheme + ".proxyPort", "http".equals(scheme) ? "80" : "443");
				return "設定なし（JVM のシステムプロパティ " + scheme + ".proxyHost のプロキシ " + systemHost + ":" + port
						+ " を使用）";
			}
			return "なし（直接接続）";
		}
		String host = null;
		try {
			host = baseUrl == null ? null : URI.create(baseUrl.trim()).getHost();
		} catch (IllegalArgumentException e) {
			// URL の誤りは接続時にエラーになる
		}
		if (host != null && proxy.bypasses(host)) {
			return "なし（" + host + " は noProxyHosts に一致するため直接接続。設定: " + proxy.describe() + "）";
		}
		return proxy.describe();
	}
}
