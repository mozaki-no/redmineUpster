package mozaki.redmineUpster.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.DiffItemEntity;

public interface DiffItemRepository extends JpaRepository<DiffItemEntity, Long> {
	List<DiffItemEntity> findByDiffIdOrderById(Long diffId);

	long countByDiffId(Long diffId);

	void deleteByDiffId(Long diffId);
}
