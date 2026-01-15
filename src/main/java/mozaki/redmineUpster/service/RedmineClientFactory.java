package mozaki.redmineUpster.service;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.domain.RedmineProjectEntity;

@Service
public class RedmineClientFactory {
	private final RestTemplate restTemplate;
	private final RedmineProperties defaultProperties;

	public RedmineClientFactory(RestTemplateBuilder builder, RedmineProperties defaultProperties) {
		this.restTemplate = builder.requestFactory(SimpleClientHttpRequestFactory::new).build();
		this.defaultProperties = defaultProperties;
	}

	public RedmineClient createClient(RedmineProjectEntity project) {
		return new RedmineClient(project.getBaseUrl(), project.getApiKey(), project.getProjectId(), restTemplate);
	}

	public RedmineClient createDefaultClient() {
		return new RedmineClient(
				defaultProperties.getBaseUrl(),
				defaultProperties.getApiKey(),
				defaultProperties.getProjectId(),
				restTemplate);
	}
}
