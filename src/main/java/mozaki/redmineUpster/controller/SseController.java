package mozaki.redmineUpster.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import mozaki.redmineUpster.service.SseEmitterService;

@RestController
@RequestMapping("/api/sse")
public class SseController {
	private final SseEmitterService sseEmitterService;

	public SseController(SseEmitterService sseEmitterService) {
		this.sseEmitterService = sseEmitterService;
	}

	@GetMapping(value = "/runs/{runId}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter streamLogs(@PathVariable Long runId) {
		return sseEmitterService.createEmitter(runId);
	}
}
