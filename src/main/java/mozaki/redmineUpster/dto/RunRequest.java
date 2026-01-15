package mozaki.redmineUpster.dto;

public class RunRequest {
	private Long diffId;
	private boolean dryRun;
	private Long redmineProjectId;

	public RunRequest() {
	}

	public RunRequest(Long diffId, boolean dryRun) {
		this.diffId = diffId;
		this.dryRun = dryRun;
	}

	public RunRequest(Long diffId, boolean dryRun, Long redmineProjectId) {
		this.diffId = diffId;
		this.dryRun = dryRun;
		this.redmineProjectId = redmineProjectId;
	}

	public Long getDiffId() {
		return diffId;
	}

	public void setDiffId(Long diffId) {
		this.diffId = diffId;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public void setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
	}

	public Long getRedmineProjectId() {
		return redmineProjectId;
	}

	public void setRedmineProjectId(Long redmineProjectId) {
		this.redmineProjectId = redmineProjectId;
	}
}
