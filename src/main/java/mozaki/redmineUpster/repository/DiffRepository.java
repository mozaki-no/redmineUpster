package mozaki.redmineUpster.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.DiffEntity;

public interface DiffRepository extends JpaRepository<DiffEntity, Long> {
}
