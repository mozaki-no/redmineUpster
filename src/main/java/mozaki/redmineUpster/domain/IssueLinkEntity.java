package mozaki.redmineUpster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "issue_link", uniqueConstraints = {
	@UniqueConstraint(columnNames = {"external_key", "project_id"})
})
public class IssueLinkEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_key", nullable = false)
	private String externalKey;

	@Column(name = "issue_id", nullable = false)
	private Long issueId;

	@Column(name = "payload_hash")
	private String payloadHash;

	@Column(name = "project_id")
	private String projectId;

	public IssueLinkEntity() {
	}

	public IssueLinkEntity(String externalKey, Long issueId) {
		this.externalKey = externalKey;
		this.issueId = issueId;
	}

	public Long getId() {
		return id;
	}

	public String getExternalKey() {
		return externalKey;
	}

	public void setExternalKey(String externalKey) {
		this.externalKey = externalKey;
	}

	public Long getIssueId() {
		return issueId;
	}

	public void setIssueId(Long issueId) {
		this.issueId = issueId;
	}

	public String getPayloadHash() {
		return payloadHash;
	}

	public void setPayloadHash(String payloadHash) {
		this.payloadHash = payloadHash;
	}

	public String getProjectId() {
		return projectId;
	}

	public void setProjectId(String projectId) {
		this.projectId = projectId;
	}
}
