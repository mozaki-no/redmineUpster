package mozaki.redmineUpster.dto;

import java.time.Instant;

public class DiffResponse {
	private Long id;
	private String filename;
	private Instant createdAt;
	private int itemCount;

	public DiffResponse(Long id, String filename, Instant createdAt, int itemCount) {
		this.id = id;
		this.filename = filename;
		this.createdAt = createdAt;
		this.itemCount = itemCount;
	}

	public Long getId() {
		return id;
	}

	public String getFilename() {
		return filename;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public int getItemCount() {
		return itemCount;
	}
}
