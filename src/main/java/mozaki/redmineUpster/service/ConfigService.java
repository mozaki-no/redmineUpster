package mozaki.redmineUpster.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mozaki.redmineUpster.domain.ConfigEntity;
import mozaki.redmineUpster.repository.ConfigRepository;

@Service
public class ConfigService {
	private final ConfigRepository configRepository;

	public ConfigService(ConfigRepository configRepository) {
		this.configRepository = configRepository;
	}

	public Map<String, String> getConfigMap() {
		Map<String, String> values = new HashMap<>();
		for (ConfigEntity entity : configRepository.findAll()) {
			values.put(entity.getConfigKey(), entity.getConfigValue());
		}
		return values;
	}

	@Transactional
	public List<ConfigEntity> upsertAll(List<ConfigEntity> items) {
		for (ConfigEntity item : items) {
			Optional<ConfigEntity> existing = configRepository.findByConfigKey(item.getConfigKey());
			if (existing.isPresent()) {
				ConfigEntity entity = existing.get();
				entity.setConfigValue(item.getConfigValue());
			} else {
				configRepository.save(item);
			}
		}
		return configRepository.findAll();
	}
}
