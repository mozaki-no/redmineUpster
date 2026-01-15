package mozaki.redmineUpster.domain;

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
@Table(name = "issue_link")
public class IssueLinkEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "external_key", nullable = false)
	private String externalKey;

	@Column(name = "issue_id", nullable = false)
	private Long issueId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "redmine_project_id")
	private RedmineProjectEntity redmineProject;

	public IssueLinkEntity() {
	}

	public IssueLinkEntity(String externalKey, Long issueId) {
		this.externalKey = externalKey;
		this.issueId = issueId;
	}

	public IssueLinkEntity(String externalKey, Long issueId, RedmineProjectEntity redmineProject) {
		this.externalKey = externalKey;
		this.issueId = issueId;
		this.redmineProject = redmineProject;
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

	public RedmineProjectEntity getRedmineProject() {
		return redmineProject;
	}

	public void setRedmineProject(RedmineProjectEntity redmineProject) {
		this.redmineProject = redmineProject;
	}
}
