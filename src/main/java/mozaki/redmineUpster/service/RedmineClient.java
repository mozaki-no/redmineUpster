package mozaki.redmineUpster.service;

import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

public class RedmineClient {
	private final String baseUrl;
	private final String apiKey;
	private final String projectId;
	private final RestTemplate restTemplate;

	public RedmineClient(String baseUrl, String apiKey, String projectId, RestTemplate restTemplate) {
		this.baseUrl = normalizeBaseUrl(baseUrl);
		this.apiKey = apiKey;
		this.projectId = projectId;
		this.restTemplate = restTemplate;
	}

	public Long createIssue(Map<String, Object> issue) {
		String url = baseUrl + "/issues.json";
		HttpEntity<Map<String, Object>> entity = buildEntity(Map.of("issue", issue));
		ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
		Map body = response.getBody();
		if (body == null || !body.containsKey("issue")) {
			return null;
		}
		Map issueData = (Map) body.get("issue");
		Object id = issueData.get("id");
		if (id instanceof Number) {
			return ((Number) id).longValue();
		}
		return null;
	}

	public void updateIssue(Long issueId, Map<String, Object> issue) {
		String url = baseUrl + "/issues/" + issueId + ".json";
		HttpEntity<Map<String, Object>> entity = buildEntity(Map.of("issue", issue));
		restTemplate.put(url, entity);
	}

	public String getProjectId() {
		return projectId;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	private HttpEntity<Map<String, Object>> buildEntity(Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Connection", "close");
		if (apiKey != null && !apiKey.isBlank()) {
			headers.set("X-Redmine-API-Key", apiKey);
		}
		return new HttpEntity<>(body, headers);
	}

	private static String normalizeBaseUrl(String base) {
		if (base != null && base.endsWith("/")) {
			return base.substring(0, base.length() - 1);
		}
		return base;
	}
}
