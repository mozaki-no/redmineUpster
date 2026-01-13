package mozaki.redmineUpster.dto;

import java.time.Instant;

public class RunResponse {
	private Long id;
	private Long diffId;
	private boolean dryRun;
	private String status;
	private Instant startedAt;
	private Instant finishedAt;

	public RunResponse(Long id, Long diffId, boolean dryRun, String status, Instant startedAt, Instant finishedAt) {
		this.id = id;
		this.diffId = diffId;
		this.dryRun = dryRun;
		this.status = status;
		this.startedAt = startedAt;
		this.finishedAt = finishedAt;
	}

	public Long getId() {
		return id;
	}

	public Long getDiffId() {
		return diffId;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public String getStatus() {
		return status;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}
}
