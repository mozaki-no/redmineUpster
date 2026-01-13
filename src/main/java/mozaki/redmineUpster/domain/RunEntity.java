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
@Table(name = "runs")
public class RunEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "diff_id", nullable = false)
	private DiffEntity diff;

	@Column(name = "dry_run", nullable = false)
	private boolean dryRun;

	@Column(name = "status", nullable = false)
	private String status;

	@Column(name = "started_at", nullable = false)
	private Instant startedAt = Instant.now();

	@Column(name = "finished_at")
	private Instant finishedAt;

	public RunEntity() {
	}

	public RunEntity(DiffEntity diff, boolean dryRun, String status) {
		this.diff = diff;
		this.dryRun = dryRun;
		this.status = status;
	}

	public Long getId() {
		return id;
	}

	public DiffEntity getDiff() {
		return diff;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public void setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public void setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
	}
}
