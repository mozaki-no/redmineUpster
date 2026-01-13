package mozaki.redmineUpster.dto;

public class DiffItemResponse {
	private Long id;
	private String externalKey;
	private String subject;
	private String parentKey;
	private String levelPath;
	private String action;
	private String status;

	public DiffItemResponse(Long id, String externalKey, String subject, String parentKey, String levelPath,
			String action, String status) {
		this.id = id;
		this.externalKey = externalKey;
		this.subject = subject;
		this.parentKey = parentKey;
		this.levelPath = levelPath;
		this.action = action;
		this.status = status;
	}

	public Long getId() {
		return id;
	}

	public String getExternalKey() {
		return externalKey;
	}

	public String getSubject() {
		return subject;
	}

	public String getParentKey() {
		return parentKey;
	}

	public String getLevelPath() {
		return levelPath;
	}

	public String getAction() {
		return action;
	}

	public String getStatus() {
		return status;
	}
}
