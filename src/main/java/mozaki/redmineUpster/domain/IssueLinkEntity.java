package mozaki.redmineUpster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * このツールが作成・更新したRedmineチケットの管理テーブル。
 * <p>
 * (issue_id, project_id) で一意です。payload_hash は前回送信内容のハッシュで、
 * 変更がない場合の更新スキップと、論理削除済みの判定に使用します。
 * external_key は旧方式（CSVのid列で紐付け）の名残で、新方式では使用しません（NULL可）。
 * </p>
 */
@Entity
@Table(name = "issue_link", uniqueConstraints = {
	@UniqueConstraint(columnNames = {"issue_id", "project_id"})
})
public class IssueLinkEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_key")
	private String externalKey;

	@Column(name = "issue_id", nullable = false)
	private Long issueId;

	@Column(name = "payload_hash")
	private String payloadHash;

	@Column(name = "project_id")
	private String projectId;

	public IssueLinkEntity() {
	}

	public IssueLinkEntity(Long issueId, String projectId) {
		this.issueId = issueId;
		this.projectId = projectId;
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
