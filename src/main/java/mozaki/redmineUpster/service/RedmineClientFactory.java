package mozaki.redmineUpster.service;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;

/**
 * Redmineクライアントファクトリ。
 * <p>
 * 各種設定からRedmineClientを生成します。
 * </p>
 */
@Service
public class RedmineClientFactory {
	private final RestTemplate restTemplate;
	private final RedmineProperties defaultProperties;

	/**
	 * RedmineClientFactoryを構築します。
	 *
	 * @param builder RestTemplateBuilder
	 * @param defaultProperties デフォルトのRedmine設定
	 */
	public RedmineClientFactory(RestTemplateBuilder builder, RedmineProperties defaultProperties) {
		// リクエストボディをバッファして Content-Length 付きで送る（chunked 送信を受け付けないサーバー・プロキシ対策）
		this.restTemplate = builder
				.requestFactory(() -> new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
				.build();
		this.defaultProperties = defaultProperties;
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
		return new RedmineClient(
				projectConfig.getRedmine().getBaseUrl(),
				projectConfig.getRedmine().getApiKey(),
				projectConfig.getRedmine().getProjectId(),
				restTemplate);
	}

	/**
	 * デフォルト設定からクライアントを生成します。
	 *
	 * @return Redmineクライアント
	 */
	public RedmineClient createDefaultClient() {
		return new RedmineClient(
				defaultProperties.getBaseUrl(),
				defaultProperties.getApiKey(),
				defaultProperties.getProjectId(),
				restTemplate);
	}
}
