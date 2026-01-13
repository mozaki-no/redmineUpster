package mozaki.redmineUpster.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.RunEntity;

public interface RunRepository extends JpaRepository<RunEntity, Long> {
}
