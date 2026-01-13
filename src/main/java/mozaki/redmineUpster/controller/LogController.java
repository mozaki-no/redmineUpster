package mozaki.redmineUpster.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import mozaki.redmineUpster.dto.RunLogResponse;
import mozaki.redmineUpster.repository.RunLogRepository;

@RestController
@RequestMapping(path = "/api/logs", produces = MediaType.APPLICATION_JSON_VALUE)
public class LogController {
	private final RunLogRepository runLogRepository;

	public LogController(RunLogRepository runLogRepository) {
		this.runLogRepository = runLogRepository;
	}

	@GetMapping
	public List<RunLogResponse> list(@RequestParam("runId") Long runId) {
		return runLogRepository.findByRunIdOrderById(runId).stream()
				.map(log -> new RunLogResponse(log.getId(), log.getLevel(), log.getMessage(), log.getCreatedAt()))
				.toList();
	}
}
