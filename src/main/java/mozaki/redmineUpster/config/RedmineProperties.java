package mozaki.redmineUpster.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "redmine")
public class RedmineProperties {
	private String baseUrl;
	private String apiKey;
	private String projectId;
	/** HTTP(S) プロキシ設定（redmine.proxy.host など。host 空欄なら直接接続） */
	private ProxyConfig proxy = new ProxyConfig();

	public ProxyConfig getProxy() {
		return proxy;
	}

	public void setProxy(ProxyConfig proxy) {
		this.proxy = proxy;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getProjectId() {
		return projectId;
	}

	public void setProjectId(String projectId) {
		this.projectId = projectId;
	}
}
