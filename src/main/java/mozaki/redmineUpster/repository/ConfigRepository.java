package mozaki.redmineUpster.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import mozaki.redmineUpster.domain.ConfigEntity;

public interface ConfigRepository extends JpaRepository<ConfigEntity, Long> {
	Optional<ConfigEntity> findByConfigKey(String configKey);
}
