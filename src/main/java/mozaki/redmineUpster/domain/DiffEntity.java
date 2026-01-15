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
@Table(name = "diffs")
public class DiffEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "filename")
	private String filename;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "redmine_project_id")
	private RedmineProjectEntity redmineProject;

	public DiffEntity() {
	}

	public DiffEntity(String filename) {
		this.filename = filename;
	}

	public Long getId() {
		return id;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public RedmineProjectEntity getRedmineProject() {
		return redmineProject;
	}

	public void setRedmineProject(RedmineProjectEntity redmineProject) {
		this.redmineProject = redmineProject;
	}
}
