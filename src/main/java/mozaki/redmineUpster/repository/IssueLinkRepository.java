package mozaki.redmineUpster.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.IssueLinkEntity;

public interface IssueLinkRepository extends JpaRepository<IssueLinkEntity, Long> {
	Optional<IssueLinkEntity> findByExternalKey(String externalKey);
	Optional<IssueLinkEntity> findByExternalKeyAndProjectId(String externalKey, String projectId);
}
