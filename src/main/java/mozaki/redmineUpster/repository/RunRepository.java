package mozaki.redmineUpster.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.RunEntity;

public interface RunRepository extends JpaRepository<RunEntity, Long> {
	List<RunEntity> findByDiffId(Long diffId);
}
