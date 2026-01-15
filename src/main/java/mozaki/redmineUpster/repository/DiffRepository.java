package mozaki.redmineUpster.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.DiffEntity;

public interface DiffRepository extends JpaRepository<DiffEntity, Long> {
	List<DiffEntity> findByRedmineProjectId(Long redmineProjectId);
}
