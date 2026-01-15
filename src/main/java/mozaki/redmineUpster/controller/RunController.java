package mozaki.redmineUpster.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mozaki.redmineUpster.domain.RunEntity;
import mozaki.redmineUpster.dto.RunRequest;
import mozaki.redmineUpster.dto.RunResponse;
import mozaki.redmineUpster.repository.RunLogRepository;
import mozaki.redmineUpster.repository.RunRepository;
import mozaki.redmineUpster.service.AsyncRunService;
import mozaki.redmineUpster.service.RunService;

@RestController
@RequestMapping(path = "/api/runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunController {
	private final RunService runService;
	private final AsyncRunService asyncRunService;
	private final RunRepository runRepository;
	private final RunLogRepository runLogRepository;

	public RunController(RunService runService, AsyncRunService asyncRunService, RunRepository runRepository,
			RunLogRepository runLogRepository) {
		this.runService = runService;
		this.asyncRunService = asyncRunService;
		this.runRepository = runRepository;
		this.runLogRepository = runLogRepository;
	}

	@GetMapping
	public List<RunResponse> list() {
		return runRepository.findAll().stream()
				.map(this::toResponse)
				.toList();
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public RunResponse run(@RequestBody RunRequest request) {
		// デフォルトで非同期実行（リアルタイムログ対応）
		RunEntity run = asyncRunService.startRun(request.getDiffId(), request.isDryRun(), request.getRedmineProjectId());
		return toResponse(run);
	}

	@PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE)
	public RunResponse runSync(@RequestBody RunRequest request) {
		// 同期実行（後方互換性のため）
		RunEntity run = runService.runDiff(request.getDiffId(), request.isDryRun(), request.getRedmineProjectId());
		return toResponse(run);
	}

	@DeleteMapping("/{runId}")
	@Transactional
	public void delete(@PathVariable("runId") Long runId) {
		runLogRepository.deleteByRunId(runId);
		runRepository.deleteById(runId);
	}

	private RunResponse toResponse(RunEntity run) {
		return new RunResponse(run.getId(), run.getDiff().getId(), run.isDryRun(), run.getStatus(),
				run.getStartedAt(), run.getFinishedAt());
	}
}
