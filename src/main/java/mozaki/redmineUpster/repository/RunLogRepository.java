package mozaki.redmineUpster.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.RunLogEntity;

public interface RunLogRepository extends JpaRepository<RunLogEntity, Long> {
	List<RunLogEntity> findByRunIdOrderById(Long runId);
}
