package mozaki.redmineUpster.dto;

public class RedmineInfoResponse {
	private String baseUrl;
	private String projectId;
	private boolean apiKeyConfigured;

	public RedmineInfoResponse(String baseUrl, String projectId, boolean apiKeyConfigured) {
		this.baseUrl = baseUrl;
		this.projectId = projectId;
		this.apiKeyConfigured = apiKeyConfigured;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public String getProjectId() {
		return projectId;
	}

	public boolean isApiKeyConfigured() {
		return apiKeyConfigured;
	}
}
