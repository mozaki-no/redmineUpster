package mozaki.redmineUpster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "issue_link")
public class IssueLinkEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_key", nullable = false, unique = true)
	private String externalKey;

	@Column(name = "issue_id", nullable = false)
	private Long issueId;

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
}
