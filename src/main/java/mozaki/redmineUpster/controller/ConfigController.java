package mozaki.redmineUpster.controller;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import mozaki.redmineUpster.domain.ConfigEntity;
import mozaki.redmineUpster.dto.ConfigItem;
import mozaki.redmineUpster.dto.ConfigUpdateRequest;
import mozaki.redmineUpster.service.ConfigService;

@RestController
@RequestMapping(path = "/api/configs", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConfigController {
	private final ConfigService configService;

	public ConfigController(ConfigService configService) {
		this.configService = configService;
	}

	@GetMapping
	public List<ConfigItem> list() {
		return configService.getConfigMap().entrySet().stream()
				.map(entry -> new ConfigItem(entry.getKey(), entry.getValue()))
				.collect(Collectors.toList());
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public List<ConfigItem> upsert(@RequestBody ConfigUpdateRequest request) {
		List<ConfigEntity> entities = request.getItems().stream()
				.map(item -> {
					ConfigEntity entity = new ConfigEntity();
					entity.setConfigKey(item.getKey());
					entity.setConfigValue(item.getValue());
					return entity;
				})
				.collect(Collectors.toList());
		return configService.upsertAll(entities).stream()
				.map(entity -> new ConfigItem(entity.getConfigKey(), entity.getConfigValue()))
				.collect(Collectors.toList());
	}
}
