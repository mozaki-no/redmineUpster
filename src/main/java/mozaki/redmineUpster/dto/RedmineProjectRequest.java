package mozaki.redmineUpster.dto;

public class RedmineProjectRequest {
	private String name;
	private String baseUrl;
	private String apiKey;
	private String projectId;
	private boolean isDefault;

	public RedmineProjectRequest() {
	}

	public RedmineProjectRequest(String name, String baseUrl, String apiKey, String projectId, boolean isDefault) {
		this.name = name;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.projectId = projectId;
		this.isDefault = isDefault;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
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

	public boolean isDefault() {
		return isDefault;
	}

	public void setDefault(boolean isDefault) {
		this.isDefault = isDefault;
	}
}
