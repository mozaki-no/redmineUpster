package mozaki.redmineUpster.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.dto.RedmineInfoResponse;

@RestController
@RequestMapping(path = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminController {
	private final RedmineProperties redmineProperties;

	public AdminController(RedmineProperties redmineProperties) {
		this.redmineProperties = redmineProperties;
	}

	@GetMapping("/redmine")
	public RedmineInfoResponse redmineInfo() {
		boolean apiKeyConfigured = redmineProperties.getApiKey() != null && !redmineProperties.getApiKey().isBlank();
		return new RedmineInfoResponse(redmineProperties.getBaseUrl(), redmineProperties.getProjectId(),
				apiKeyConfigured);
	}
}
