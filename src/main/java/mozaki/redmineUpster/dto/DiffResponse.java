package mozaki.redmineUpster.dto;

import java.time.Instant;

public class DiffResponse {
	private Long id;
	private String filename;
	private Instant createdAt;
	private int itemCount;
	private Long redmineProjectId;
	private String redmineProjectName;

	public DiffResponse(Long id, String filename, Instant createdAt, int itemCount) {
		this(id, filename, createdAt, itemCount, null, null);
	}

	public DiffResponse(Long id, String filename, Instant createdAt, int itemCount,
			Long redmineProjectId, String redmineProjectName) {
		this.id = id;
		this.filename = filename;
		this.createdAt = createdAt;
		this.itemCount = itemCount;
		this.redmineProjectId = redmineProjectId;
		this.redmineProjectName = redmineProjectName;
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

	public Long getRedmineProjectId() {
		return redmineProjectId;
	}

	public String getRedmineProjectName() {
		return redmineProjectName;
	}
}
