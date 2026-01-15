package mozaki.redmineUpster.dto;

import java.time.Instant;

import mozaki.redmineUpster.domain.ScheduleEntity;

public class ScheduleResponse {
	private Long id;
	private String name;
	private String cronExpression;
	private Long redmineProjectId;
	private String redmineProjectName;
	private String wbsFilePath;
	private boolean dryRun;
	private boolean enabled;
	private Instant lastRunAt;
	private Instant nextRunAt;
	private Instant createdAt;

	public ScheduleResponse(Long id, String name, String cronExpression, Long redmineProjectId,
			String redmineProjectName, String wbsFilePath, boolean dryRun, boolean enabled,
			Instant lastRunAt, Instant nextRunAt, Instant createdAt) {
		this.id = id;
		this.name = name;
		this.cronExpression = cronExpression;
		this.redmineProjectId = redmineProjectId;
		this.redmineProjectName = redmineProjectName;
		this.wbsFilePath = wbsFilePath;
		this.dryRun = dryRun;
		this.enabled = enabled;
		this.lastRunAt = lastRunAt;
		this.nextRunAt = nextRunAt;
		this.createdAt = createdAt;
	}

	public static ScheduleResponse from(ScheduleEntity entity) {
		return new ScheduleResponse(
				entity.getId(),
				entity.getName(),
				entity.getCronExpression(),
				entity.getRedmineProject().getId(),
				entity.getRedmineProject().getName(),
				entity.getWbsFilePath(),
				entity.isDryRun(),
				entity.isEnabled(),
				entity.getLastRunAt(),
				entity.getNextRunAt(),
				entity.getCreatedAt());
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCronExpression() {
		return cronExpression;
	}

	public Long getRedmineProjectId() {
		return redmineProjectId;
	}

	public String getRedmineProjectName() {
		return redmineProjectName;
	}

	public String getWbsFilePath() {
		return wbsFilePath;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public Instant getLastRunAt() {
		return lastRunAt;
	}

	public Instant getNextRunAt() {
		return nextRunAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
