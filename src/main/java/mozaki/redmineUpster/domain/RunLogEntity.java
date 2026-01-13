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
@Table(name = "run_logs")
public class RunLogEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "run_id", nullable = false)
	private RunEntity run;

	@Column(name = "level", nullable = false)
	private String level;

	@Column(name = "message", nullable = false)
	private String message;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public RunLogEntity() {
	}

	public RunLogEntity(RunEntity run, String level, String message) {
		this.run = run;
		this.level = level;
		this.message = message;
	}

	public Long getId() {
		return id;
	}

	public RunEntity getRun() {
		return run;
	}

	public String getLevel() {
		return level;
	}

	public String getMessage() {
		return message;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
