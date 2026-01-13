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
@Table(name = "diff_items")
public class DiffItemEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "diff_id", nullable = false)
	private DiffEntity diff;

	@Column(name = "external_key", nullable = false)
	private String externalKey;

	@Column(name = "subject")
	private String subject;

	@Column(name = "parent_key")
	private String parentKey;

	@Column(name = "level_path")
	private String levelPath;

	@Column(name = "action", nullable = false)
	private String action;

	@Column(name = "status")
	private String status;

	@Column(name = "payload_json")
	private String payloadJson;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public DiffItemEntity() {
	}

	public DiffItemEntity(DiffEntity diff, String externalKey, String subject, String parentKey, String levelPath,
			String action, String status) {
		this.diff = diff;
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

	public DiffEntity getDiff() {
		return diff;
	}

	public String getExternalKey() {
		return externalKey;
	}

	public void setExternalKey(String externalKey) {
		this.externalKey = externalKey;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getParentKey() {
		return parentKey;
	}

	public void setParentKey(String parentKey) {
		this.parentKey = parentKey;
	}

	public String getLevelPath() {
		return levelPath;
	}

	public void setLevelPath(String levelPath) {
		this.levelPath = levelPath;
	}

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getPayloadJson() {
		return payloadJson;
	}

	public void setPayloadJson(String payloadJson) {
		this.payloadJson = payloadJson;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
