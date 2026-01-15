package mozaki.redmineUpster.dto;

public class ScheduleRequest {
	private String name;
	private String cronExpression;
	private Long redmineProjectId;
	private String wbsFilePath;
	private boolean dryRun;
	private boolean enabled = true;

	public ScheduleRequest() {
	}

	public ScheduleRequest(String name, String cronExpression, Long redmineProjectId, String wbsFilePath,
			boolean dryRun, boolean enabled) {
		this.name = name;
		this.cronExpression = cronExpression;
		this.redmineProjectId = redmineProjectId;
		this.wbsFilePath = wbsFilePath;
		this.dryRun = dryRun;
		this.enabled = enabled;
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

	public Long getRedmineProjectId() {
		return redmineProjectId;
	}

	public void setRedmineProjectId(Long redmineProjectId) {
		this.redmineProjectId = redmineProjectId;
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
}
