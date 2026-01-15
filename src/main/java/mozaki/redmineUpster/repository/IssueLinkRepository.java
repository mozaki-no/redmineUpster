package mozaki.redmineUpster.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.IssueLinkEntity;
import mozaki.redmineUpster.domain.RedmineProjectEntity;

public interface IssueLinkRepository extends JpaRepository<IssueLinkEntity, Long> {
	Optional<IssueLinkEntity> findByExternalKey(String externalKey);

	Optional<IssueLinkEntity> findByExternalKeyAndRedmineProject(String externalKey, RedmineProjectEntity redmineProject);

	void deleteByRedmineProjectId(Long redmineProjectId);
}
