package mozaki.redmineUpster.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mozaki.redmineUpster.dto.RedmineProjectRequest;
import mozaki.redmineUpster.dto.RedmineProjectResponse;
import mozaki.redmineUpster.service.RedmineProjectService;

@RestController
@RequestMapping(path = "/api/redmine-projects", produces = MediaType.APPLICATION_JSON_VALUE)
public class RedmineProjectController {
	private final RedmineProjectService redmineProjectService;

	public RedmineProjectController(RedmineProjectService redmineProjectService) {
		this.redmineProjectService = redmineProjectService;
	}

	@GetMapping
	public List<RedmineProjectResponse> list() {
		return redmineProjectService.findAll().stream()
				.map(RedmineProjectResponse::from)
				.collect(Collectors.toList());
	}

	@GetMapping("/{id}")
	public RedmineProjectResponse get(@PathVariable Long id) {
		return redmineProjectService.findById(id)
				.map(RedmineProjectResponse::from)
				.orElseThrow(() -> new IllegalArgumentException("RedmineProject not found: " + id));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public RedmineProjectResponse create(@RequestBody RedmineProjectRequest request) {
		return RedmineProjectResponse.from(redmineProjectService.create(request));
	}

	@PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public RedmineProjectResponse update(@PathVariable Long id, @RequestBody RedmineProjectRequest request) {
		return RedmineProjectResponse.from(redmineProjectService.update(id, request));
	}

	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		redmineProjectService.delete(id);
	}

	@PostMapping("/{id}/test")
	public Map<String, Object> testConnection(@PathVariable Long id) {
		boolean success = redmineProjectService.testConnection(id);
		return Map.of(
				"success", success,
				"message", success ? "接続成功" : "接続失敗");
	}
}
