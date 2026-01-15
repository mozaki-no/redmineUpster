package mozaki.redmineUpster.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "schedules")
public class ScheduleEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "cron_expression", nullable = false)
	private String cronExpression;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "redmine_project_id", nullable = false)
	private RedmineProjectEntity redmineProject;

	@Column(name = "wbs_file_path")
	private String wbsFilePath;

	@Column(name = "dry_run")
	private boolean dryRun = false;

	@Column(name = "enabled")
	private boolean enabled = true;

	@Column(name = "last_run_at")
	private Instant lastRunAt;

	@Column(name = "next_run_at")
	private Instant nextRunAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public ScheduleEntity() {
	}

	public ScheduleEntity(String name, String cronExpression, RedmineProjectEntity redmineProject) {
		this.name = name;
		this.cronExpression = cronExpression;
		this.redmineProject = redmineProject;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getCronExpression() {
		return cronExpression;
	}

	public void setCronExpression(String cronExpression) {
		this.cronExpression = cronExpression;
	}

	public RedmineProjectEntity getRedmineProject() {
		return redmineProject;
	}

	public void setRedmineProject(RedmineProjectEntity redmineProject) {
		this.redmineProject = redmineProject;
	}

	public String getWbsFilePath() {
		return wbsFilePath;
	}

	public void setWbsFilePath(String wbsFilePath) {
		this.wbsFilePath = wbsFilePath;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public void setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Instant getLastRunAt() {
		return lastRunAt;
	}

	public void setLastRunAt(Instant lastRunAt) {
		this.lastRunAt = lastRunAt;
	}

	public Instant getNextRunAt() {
		return nextRunAt;
	}

	public void setNextRunAt(Instant nextRunAt) {
		this.nextRunAt = nextRunAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
