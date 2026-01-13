package mozaki.redmineUpster.service;

import java.util.Map;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import mozaki.redmineUpster.config.RedmineProperties;

@Service
public class RedmineClient {
	private final RestTemplate restTemplate;
	private final RedmineProperties properties;

	public RedmineClient(RestTemplateBuilder builder, RedmineProperties properties) {
		this.properties = properties;
		this.restTemplate = builder.build();
	}

	public Long createIssue(Map<String, Object> issue) {
		String url = baseUrl() + "/issues.json";
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
		String url = baseUrl() + "/issues/" + issueId + ".json";
		HttpEntity<Map<String, Object>> entity = buildEntity(Map.of("issue", issue));
		restTemplate.put(url, entity);
	}

	private HttpEntity<Map<String, Object>> buildEntity(Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
			headers.set("X-Redmine-API-Key", properties.getApiKey());
		}
		return new HttpEntity<>(body, headers);
	}

	private String baseUrl() {
		String base = properties.getBaseUrl();
		if (base.endsWith("/")) {
			return base.substring(0, base.length() - 1);
		}
		return base;
	}
}
