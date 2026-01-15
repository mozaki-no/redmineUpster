package mozaki.redmineUpster.controller;

import java.util.List;
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

import mozaki.redmineUpster.dto.ScheduleRequest;
import mozaki.redmineUpster.dto.ScheduleResponse;
import mozaki.redmineUpster.service.ScheduleService;

@RestController
@RequestMapping(path = "/api/schedules", produces = MediaType.APPLICATION_JSON_VALUE)
public class ScheduleController {
	private final ScheduleService scheduleService;

	public ScheduleController(ScheduleService scheduleService) {
		this.scheduleService = scheduleService;
	}

	@GetMapping
	public List<ScheduleResponse> list() {
		return scheduleService.findAll().stream()
				.map(ScheduleResponse::from)
				.collect(Collectors.toList());
	}

	@GetMapping("/{id}")
	public ScheduleResponse get(@PathVariable Long id) {
		return scheduleService.findById(id)
				.map(ScheduleResponse::from)
				.orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ScheduleResponse create(@RequestBody ScheduleRequest request) {
		return ScheduleResponse.from(scheduleService.create(request));
	}

	@PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ScheduleResponse update(@PathVariable Long id, @RequestBody ScheduleRequest request) {
		return ScheduleResponse.from(scheduleService.update(id, request));
	}

	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		scheduleService.delete(id);
	}

	@PostMapping("/{id}/toggle")
	public ScheduleResponse toggle(@PathVariable Long id) {
		return ScheduleResponse.from(scheduleService.toggle(id));
	}
}
