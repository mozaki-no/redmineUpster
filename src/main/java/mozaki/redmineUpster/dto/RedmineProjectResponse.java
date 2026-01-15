package mozaki.redmineUpster.dto;

import java.time.Instant;

import mozaki.redmineUpster.domain.RedmineProjectEntity;

public class RedmineProjectResponse {
	private Long id;
	private String name;
	private String baseUrl;
	private String projectId;
	private boolean isDefault;
	private boolean apiKeyConfigured;
	private Instant createdAt;

	public RedmineProjectResponse(Long id, String name, String baseUrl, String projectId, boolean isDefault,
			boolean apiKeyConfigured, Instant createdAt) {
		this.id = id;
		this.name = name;
		this.baseUrl = baseUrl;
		this.projectId = projectId;
		this.isDefault = isDefault;
		this.apiKeyConfigured = apiKeyConfigured;
		this.createdAt = createdAt;
	}

	public static RedmineProjectResponse from(RedmineProjectEntity entity) {
		return new RedmineProjectResponse(
				entity.getId(),
				entity.getName(),
				entity.getBaseUrl(),
				entity.getProjectId(),
				entity.isDefault(),
				entity.getApiKey() != null && !entity.getApiKey().isBlank(),
				entity.getCreatedAt());
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public String getProjectId() {
		return projectId;
	}

	public boolean isDefault() {
		return isDefault;
	}

	public boolean isApiKeyConfigured() {
		return apiKeyConfigured;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
