package mozaki.redmineUpster.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import mozaki.redmineUpster.domain.DiffEntity;
import mozaki.redmineUpster.domain.DiffItemEntity;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.domain.RedmineProjectEntity;
import mozaki.redmineUpster.domain.RunEntity;
import mozaki.redmineUpster.domain.RunLogEntity;
import mozaki.redmineUpster.repository.DiffItemRepository;
import mozaki.redmineUpster.repository.DiffRepository;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.repository.RedmineProjectRepository;
import mozaki.redmineUpster.repository.RunLogRepository;
import mozaki.redmineUpster.repository.RunRepository;
import mozaki.redmineUpster.util.DateParser;

@Service
public class AsyncRunService {
	private final RunRepository runRepository;
	private final RunLogRepository runLogRepository;
	private final DiffRepository diffRepository;
	private final DiffItemRepository diffItemRepository;
	private final IssueLinkRepository issueLinkRepository;
	private final RedmineProjectRepository redmineProjectRepository;
	private final ConfigService configService;
	private final RedmineClientFactory redmineClientFactory;
	private final SseEmitterService sseEmitterService;
	private final ObjectMapper objectMapper;

	public AsyncRunService(RunRepository runRepository, RunLogRepository runLogRepository,
			DiffRepository diffRepository, DiffItemRepository diffItemRepository,
			IssueLinkRepository issueLinkRepository, RedmineProjectRepository redmineProjectRepository,
			ConfigService configService, RedmineClientFactory redmineClientFactory,
			SseEmitterService sseEmitterService, ObjectMapper objectMapper) {
		this.runRepository = runRepository;
		this.runLogRepository = runLogRepository;
		this.diffRepository = diffRepository;
		this.diffItemRepository = diffItemRepository;
		this.issueLinkRepository = issueLinkRepository;
		this.redmineProjectRepository = redmineProjectRepository;
		this.configService = configService;
		this.redmineClientFactory = redmineClientFactory;
		this.sseEmitterService = sseEmitterService;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public RunEntity startRun(Long diffId, boolean dryRun, Long redmineProjectId) {
		DiffEntity diff = diffRepository.findById(diffId)
				.orElseThrow(() -> new IllegalArgumentException("diff not found: " + diffId));
		RunEntity run = runRepository.save(new RunEntity(diff, dryRun, "RUNNING"));
		executeAsync(run.getId(), diffId, dryRun, redmineProjectId);
		return run;
	}

	@Async("taskExecutor")
	public CompletableFuture<Void> executeAsync(Long runId, Long diffId, boolean dryRun, Long redmineProjectId) {
		try {
			executeRun(runId, diffId, dryRun, redmineProjectId);
		} catch (Exception e) {
			updateRunStatus(runId, "FAILED");
			sseEmitterService.sendLog(runId, "ERROR", "Unexpected error: " + e.getMessage());
			sseEmitterService.sendComplete(runId, "FAILED");
		}
		return CompletableFuture.completedFuture(null);
	}

	private void executeRun(Long runId, Long diffId, boolean dryRun, Long redmineProjectId) {
		DiffEntity diff = diffRepository.findById(diffId).orElse(null);
		if (diff == null) {
			updateRunStatus(runId, "FAILED");
			return;
		}

		RedmineProjectEntity project = resolveProject(redmineProjectId, diff);
		RedmineClient client = project != null
				? redmineClientFactory.createClient(project)
				: redmineClientFactory.createDefaultClient();

		Map<String, String> configMap = configService.getConfigMap();
		Map<String, String> customFieldMap = parseCustomFieldMap(configMap);
		Map<String, Long> createdIssueIds = new HashMap<>();
		boolean hasErrors = false;

		List<DiffItemEntity> items = new ArrayList<>(diffItemRepository.findByDiffIdOrderById(diffId));
		items.sort(Comparator.comparingInt(this::depth));

		int total = items.size();
		int processed = 0;

		sseEmitterService.sendProgress(runId, total, processed, "開始中...");

		for (DiffItemEntity item : items) {
			try {
				if (dryRun) {
					String msg = "DRY_RUN " + item.getAction() + " " + item.getExternalKey() + " " + item.getSubject();
					log(runId, "INFO", msg);
					sseEmitterService.sendLog(runId, "INFO", msg);
				} else {
					Map<String, Object> issuePayload = buildIssuePayload(item, client, configMap, customFieldMap,
							createdIssueIds, project, runId);
					if ("CREATE".equalsIgnoreCase(item.getAction())) {
						Long issueId = client.createIssue(issuePayload);
						if (issueId == null) {
							hasErrors = true;
							String msg = "create failed: " + item.getExternalKey();
							log(runId, "ERROR", msg);
							sseEmitterService.sendLog(runId, "ERROR", msg);
						} else {
							IssueLinkEntity link = new IssueLinkEntity(item.getExternalKey(), issueId, project);
							issueLinkRepository.save(link);
							createdIssueIds.put(item.getExternalKey(), issueId);
							String msg = "created issue " + issueId + " for " + item.getExternalKey();
							log(runId, "INFO", msg);
							sseEmitterService.sendLog(runId, "INFO", msg);
						}
					} else {
						Optional<IssueLinkEntity> link = findIssueLink(item.getExternalKey(), project);
						if (link.isEmpty()) {
							hasErrors = true;
							String msg = "missing issue link for " + item.getExternalKey();
							log(runId, "ERROR", msg);
							sseEmitterService.sendLog(runId, "ERROR", msg);
						} else {
							client.updateIssue(link.get().getIssueId(), issuePayload);
							String msg = "updated issue " + link.get().getIssueId() + " for " + item.getExternalKey();
							log(runId, "INFO", msg);
							sseEmitterService.sendLog(runId, "INFO", msg);
						}
					}
				}
			} catch (RuntimeException ex) {
				hasErrors = true;
				String msg = "sync failed for " + item.getExternalKey() + ": " + ex.getMessage();
				log(runId, "ERROR", msg);
				sseEmitterService.sendLog(runId, "ERROR", msg);
			}

			processed++;
			sseEmitterService.sendProgress(runId, total, processed, item.getSubject());
		}

		String finalStatus = dryRun ? "DRY_RUN" : (hasErrors ? "FAILED" : "SUCCESS");
		updateRunStatus(runId, finalStatus);
		sseEmitterService.sendComplete(runId, finalStatus);
	}

	private void updateRunStatus(Long runId, String status) {
		runRepository.findById(runId).ifPresent(run -> {
			run.setStatus(status);
			run.setFinishedAt(Instant.now());
			runRepository.save(run);
		});
	}

	private void log(Long runId, String level, String message) {
		runRepository.findById(runId).ifPresent(run -> {
			runLogRepository.save(new RunLogEntity(run, level, message));
		});
	}

	private RedmineProjectEntity resolveProject(Long redmineProjectId, DiffEntity diff) {
		if (redmineProjectId != null) {
			return redmineProjectRepository.findById(redmineProjectId).orElse(null);
		}
		if (diff.getRedmineProject() != null) {
			return diff.getRedmineProject();
		}
		return redmineProjectRepository.findByIsDefaultTrue().orElse(null);
	}

	private Optional<IssueLinkEntity> findIssueLink(String externalKey, RedmineProjectEntity project) {
		if (project != null) {
			return issueLinkRepository.findByExternalKeyAndRedmineProject(externalKey, project);
		}
		return issueLinkRepository.findByExternalKey(externalKey);
	}

	private Map<String, Object> buildIssuePayload(DiffItemEntity item, RedmineClient client,
			Map<String, String> configMap, Map<String, String> customFieldMap,
			Map<String, Long> createdIssueIds, RedmineProjectEntity project, Long runId) {
		Map<String, Object> issue = new HashMap<>();
		String projectId = client.getProjectId();
		if (projectId == null || projectId.isBlank()) {
			throw new IllegalStateException("redmine.project-id is not configured");
		}
		issue.put("project_id", projectId);
		issue.put("subject", item.getSubject());

		Payload payload = parsePayload(item.getPayloadJson());
		String assignee = payload.assignee();
		if (assignee != null && !assignee.isBlank()) {
			if (isNumeric(assignee)) {
				issue.put("assigned_to_id", Long.parseLong(assignee));
			}
		}

		String startDate = DateParser.normalizeDate(payload.startDate());
		if (startDate != null) {
			issue.put("start_date", startDate);
		}
		String dueDate = DateParser.normalizeDate(payload.dueDate());
		if (dueDate != null) {
			issue.put("due_date", dueDate);
		}

		Long parentIssueId = resolveParentIssueId(item.getParentKey(), createdIssueIds, project);
		if (parentIssueId != null) {
			issue.put("parent_issue_id", parentIssueId);
		}

		String trackerValue = configMap.get("tracker.auto.value");
		if (isEnabled(configMap.get("tracker.auto.enabled")) && trackerValue != null && !trackerValue.isBlank()) {
			if (isNumeric(trackerValue)) {
				issue.put("tracker_id", Long.parseLong(trackerValue));
			} else {
				issue.put("tracker", trackerValue);
			}
		}

		if (isEnabled(configMap.get("status.auto.enabled"))) {
			String statusValue = resolveStatus(item, payload, configMap);
			if (statusValue != null && !statusValue.isBlank()) {
				if (isNumeric(statusValue)) {
					issue.put("status_id", Long.parseLong(statusValue));
				} else {
					issue.put("status", statusValue);
				}
			}
		}

		List<Map<String, Object>> customFields = buildCustomFields(payload.customFields(), customFieldMap);
		if (!customFields.isEmpty()) {
			issue.put("custom_fields", customFields);
		}
		return issue;
	}

	private Long resolveParentIssueId(String parentKey, Map<String, Long> createdIssueIds,
			RedmineProjectEntity project) {
		if (parentKey == null || parentKey.isBlank()) {
			return null;
		}
		Long created = createdIssueIds.get(parentKey);
		if (created != null) {
			return created;
		}
		Optional<IssueLinkEntity> link = findIssueLink(parentKey, project);
		return link.map(IssueLinkEntity::getIssueId).orElse(null);
	}

	private List<Map<String, Object>> buildCustomFields(Map<String, String> values, Map<String, String> mapping) {
		List<Map<String, Object>> customFields = new ArrayList<>();
		if (values == null || values.isEmpty()) {
			return customFields;
		}
		for (Map.Entry<String, String> entry : values.entrySet()) {
			String column = entry.getKey();
			String value = entry.getValue();
			if (value == null || value.isBlank()) {
				continue;
			}
			String field = mapping.get(column);
			if (field == null || field.isBlank()) {
				continue;
			}
			Map<String, Object> cf = new HashMap<>();
			if (isNumeric(field)) {
				cf.put("id", Long.parseLong(field));
			} else {
				cf.put("name", field);
			}
			cf.put("value", value);
			customFields.add(cf);
		}
		return customFields;
	}

	private String resolveStatus(DiffItemEntity item, Payload payload, Map<String, String> configMap) {
		if (item.getStatus() != null && !item.getStatus().isBlank()) {
			return item.getStatus();
		}
		String mode = valueOrDefault(configMap.get("status.auto.mode"), "BY_DATES");
		if ("FIXED".equalsIgnoreCase(mode)) {
			return valueOrDefault(configMap.get("status.auto.fixed"), "New");
		}
		if (payload.dueActual() != null && !payload.dueActual().isBlank()) {
			return "Closed";
		}
		if (payload.startActual() != null && !payload.startActual().isBlank()) {
			return "In Progress";
		}
		return "New";
	}

	private Payload parsePayload(String json) {
		if (json == null || json.isBlank()) {
			return new Payload(null, null, null, null, null, Map.of());
		}
		try {
			return objectMapper.readValue(json, Payload.class);
		} catch (JsonProcessingException ex) {
			return new Payload(null, null, null, null, null, Map.of());
		}
	}

	private Map<String, String> parseCustomFieldMap(Map<String, String> configMap) {
		String raw = configMap.get("customFieldMap");
		if (raw == null || raw.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(raw,
					objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class));
		} catch (JsonProcessingException ex) {
			return Map.of();
		}
	}

	private boolean isEnabled(String value) {
		return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value);
	}

	private String valueOrDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private boolean isNumeric(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			if (!Character.isDigit(value.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private int depth(DiffItemEntity item) {
		String path = item.getLevelPath();
		if (path == null || path.isBlank()) {
			return 0;
		}
		return path.split(">").length;
	}

	public record Payload(String assignee, String startDate, String dueDate, String startActual, String dueActual,
			Map<String, String> customFields) {
	}
}
