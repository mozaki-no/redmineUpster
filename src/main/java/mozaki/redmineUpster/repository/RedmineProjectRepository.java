package mozaki.redmineUpster.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.RedmineProjectEntity;

public interface RedmineProjectRepository extends JpaRepository<RedmineProjectEntity, Long> {
	Optional<RedmineProjectEntity> findByIsDefaultTrue();
}
