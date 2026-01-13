package mozaki.redmineUpster.dto;

import java.time.Instant;

public class RunLogResponse {
	private Long id;
	private String level;
	private String message;
	private Instant createdAt;

	public RunLogResponse(Long id, String level, String message, Instant createdAt) {
		this.id = id;
		this.level = level;
		this.message = message;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
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
