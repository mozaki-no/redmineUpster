package mozaki.redmineUpster.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import mozaki.redmineUpster.domain.DiffEntity;
import mozaki.redmineUpster.domain.DiffItemEntity;
import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.repository.DiffItemRepository;
import mozaki.redmineUpster.repository.DiffRepository;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.util.ColumnDefinitions;

@Service
public class DiffService {
	private final DiffRepository diffRepository;
	private final DiffItemRepository diffItemRepository;
	private final IssueLinkRepository issueLinkRepository;
	private final ObjectMapper objectMapper;

	public DiffService(DiffRepository diffRepository, DiffItemRepository diffItemRepository,
			IssueLinkRepository issueLinkRepository, ObjectMapper objectMapper) {
		this.diffRepository = diffRepository;
		this.diffItemRepository = diffItemRepository;
		this.issueLinkRepository = issueLinkRepository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public DiffEntity createDiff(String filename, List<Map<String, String>> rows, Map<String, String> configMap) {
		DiffEntity diff = diffRepository.save(new DiffEntity(filename));
		List<RowData> parsed = new ArrayList<>();
		for (Map<String, String> row : rows) {
			String externalKey = value(row, ColumnDefinitions.COL_ID);
			if (externalKey.isBlank()) {
				continue;
			}
			List<String> hierarchy = hierarchyValues(row);
			String subject = resolveSubject(row, hierarchy);
			String levelPath = String.join(" > ", hierarchy);
			List<String> parentHierarchy = hierarchy.size() > 1 ? hierarchy.subList(0, hierarchy.size() - 1) : List.of();
			String parentPath = String.join(" > ", parentHierarchy);
			RowData data = new RowData(externalKey, subject, levelPath, parentPath, row);
			parsed.add(data);
		}

		Map<String, String> pathToExternalKey = new HashMap<>();
		for (RowData rowData : parsed) {
			if (!rowData.levelPath.isBlank() && !pathToExternalKey.containsKey(rowData.levelPath)) {
				pathToExternalKey.put(rowData.levelPath, rowData.externalKey);
			}
		}

		Map<String, String> customFieldMap = parseCustomFieldMap(configMap);
		for (RowData rowData : parsed) {
			String parentKey = pathToExternalKey.get(rowData.parentPath);
			String action = resolveAction(rowData.externalKey);
			String status = resolveStatus(rowData, configMap);
			DiffItemEntity item = new DiffItemEntity(diff, rowData.externalKey, rowData.subject, parentKey,
					rowData.levelPath, action, status);
			item.setPayloadJson(serializePayload(buildPayload(rowData, customFieldMap)));
			diffItemRepository.save(item);
		}
		return diff;
	}

	private String resolveAction(String externalKey) {
		Optional<IssueLinkEntity> existing = issueLinkRepository.findByExternalKey(externalKey);
		return existing.isPresent() ? "UPDATE" : "CREATE";
	}

	private List<String> hierarchyValues(Map<String, String> row) {
		List<String> values = new ArrayList<>();
		for (String column : ColumnDefinitions.HIERARCHY_COLUMNS) {
			String value = value(row, column);
			if (!value.isBlank()) {
				values.add(value);
			}
		}
		return values;
	}

	private String resolveSubject(Map<String, String> row, List<String> hierarchy) {
		String task = value(row, ColumnDefinitions.COL_TASK);
		if (!task.isBlank()) {
			return task;
		}
		if (!hierarchy.isEmpty()) {
			return hierarchy.get(hierarchy.size() - 1);
		}
		return "";
	}

	private String resolveStatus(RowData rowData, Map<String, String> configMap) {
		if (!isEnabled(configMap.get("status.auto.enabled"))) {
			return null;
		}
		String mode = valueOrDefault(configMap.get("status.auto.mode"), "BY_DATES");
		if ("FIXED".equalsIgnoreCase(mode)) {
			return valueOrDefault(configMap.get("status.auto.fixed"), "New");
		}
		if (!rowData.dueActual.isBlank()) {
			return "Closed";
		}
		if (!rowData.startActual.isBlank()) {
			return "In Progress";
		}
		return "New";
	}

	private Map<String, String> parseCustomFieldMap(Map<String, String> configMap) {
		String raw = configMap.get("customFieldMap");
		if (raw == null || raw.isBlank()) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(raw, objectMapper.getTypeFactory().constructMapType(Map.class, String.class,
					String.class));
		} catch (JsonProcessingException ex) {
			return Map.of();
		}
	}

	private Payload buildPayload(RowData rowData, Map<String, String> customFieldMap) {
		Map<String, String> customFields = new LinkedHashMap<>();
		for (String column : ColumnDefinitions.CUSTOM_FIELD_COLUMNS) {
			String mapping = customFieldMap.get(column);
			if (mapping == null) {
				continue;
			}
			String value = value(rowData.row, column);
			if (!value.isBlank()) {
				customFields.put(column, value);
			}
		}
		return new Payload(rowData.assignee, rowData.startPlan, rowData.duePlan, rowData.startActual,
				rowData.dueActual, customFields);
	}

	private String serializePayload(Payload payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JsonProcessingException ex) {
			return null;
		}
	}

	private boolean isEnabled(String value) {
		return "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value);
	}

	private static String value(Map<String, String> row, String key) {
		String raw = row.get(key);
		return raw == null ? "" : raw.trim();
	}

	private String valueOrDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static class RowData {
		private final String externalKey;
		private final String subject;
		private final String levelPath;
		private final String parentPath;
		private final Map<String, String> row;
		private final String assignee;
		private final String startPlan;
		private final String duePlan;
		private final String startActual;
		private final String dueActual;

		private RowData(String externalKey, String subject, String levelPath, String parentPath,
				Map<String, String> row) {
			this.externalKey = externalKey;
			this.subject = subject;
			this.levelPath = levelPath;
			this.parentPath = parentPath;
			this.row = row;
			this.assignee = value(row, ColumnDefinitions.COL_ASSIGNEE);
			this.startPlan = value(row, ColumnDefinitions.COL_START_PLAN);
			this.duePlan = value(row, ColumnDefinitions.COL_DUE_PLAN);
			this.startActual = value(row, ColumnDefinitions.COL_START_ACTUAL);
			this.dueActual = value(row, ColumnDefinitions.COL_DUE_ACTUAL);
		}
	}

	public record Payload(String assignee, String startDate, String dueDate, String startActual, String dueActual,
			Map<String, String> customFields) {
	}
}
