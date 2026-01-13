package mozaki.redmineUpster.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mozaki.redmineUpster.domain.RunEntity;
import mozaki.redmineUpster.dto.RunRequest;
import mozaki.redmineUpster.dto.RunResponse;
import mozaki.redmineUpster.repository.RunRepository;
import mozaki.redmineUpster.service.RunService;

@RestController
@RequestMapping(path = "/api/runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunController {
	private final RunService runService;
	private final RunRepository runRepository;

	public RunController(RunService runService, RunRepository runRepository) {
		this.runService = runService;
		this.runRepository = runRepository;
	}

	@GetMapping
	public List<RunResponse> list() {
		return runRepository.findAll().stream()
				.map(this::toResponse)
				.toList();
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public RunResponse run(@RequestBody RunRequest request) {
		RunEntity run = runService.runDiff(request.getDiffId(), request.isDryRun());
		return toResponse(run);
	}

	private RunResponse toResponse(RunEntity run) {
		return new RunResponse(run.getId(), run.getDiff().getId(), run.isDryRun(), run.getStatus(),
				run.getStartedAt(), run.getFinishedAt());
	}
}
