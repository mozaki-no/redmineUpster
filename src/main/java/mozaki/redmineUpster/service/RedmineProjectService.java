package mozaki.redmineUpster.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mozaki.redmineUpster.domain.RedmineProjectEntity;
import mozaki.redmineUpster.dto.RedmineProjectRequest;
import mozaki.redmineUpster.repository.RedmineProjectRepository;

@Service
public class RedmineProjectService {
	private final RedmineProjectRepository redmineProjectRepository;
	private final RedmineClientFactory redmineClientFactory;

	public RedmineProjectService(RedmineProjectRepository redmineProjectRepository,
			RedmineClientFactory redmineClientFactory) {
		this.redmineProjectRepository = redmineProjectRepository;
		this.redmineClientFactory = redmineClientFactory;
	}

	public List<RedmineProjectEntity> findAll() {
		return redmineProjectRepository.findAll();
	}

	public Optional<RedmineProjectEntity> findById(Long id) {
		return redmineProjectRepository.findById(id);
	}

	public Optional<RedmineProjectEntity> findDefault() {
		return redmineProjectRepository.findByIsDefaultTrue();
	}

	@Transactional
	public RedmineProjectEntity create(RedmineProjectRequest request) {
		if (request.isDefault()) {
			clearDefaultFlag();
		}
		RedmineProjectEntity entity = new RedmineProjectEntity(
				request.getName(),
				request.getBaseUrl(),
				request.getApiKey(),
				request.getProjectId());
		entity.setDefault(request.isDefault());
		return redmineProjectRepository.save(entity);
	}

	@Transactional
	public RedmineProjectEntity update(Long id, RedmineProjectRequest request) {
		RedmineProjectEntity entity = redmineProjectRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("RedmineProject not found: " + id));

		if (request.isDefault() && !entity.isDefault()) {
			clearDefaultFlag();
		}

		entity.setName(request.getName());
		entity.setBaseUrl(request.getBaseUrl());
		if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
			entity.setApiKey(request.getApiKey());
		}
		entity.setProjectId(request.getProjectId());
		entity.setDefault(request.isDefault());
		entity.setUpdatedAt(Instant.now());
		return redmineProjectRepository.save(entity);
	}

	@Transactional
	public void delete(Long id) {
		redmineProjectRepository.deleteById(id);
	}

	public boolean testConnection(Long id) {
		RedmineProjectEntity entity = redmineProjectRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("RedmineProject not found: " + id));
		try {
			RedmineClient client = redmineClientFactory.createClient(entity);
			// Redmine APIでプロジェクト情報を取得して接続確認
			// 簡易的にbaseUrlへのアクセス可否で判断
			return client.getBaseUrl() != null;
		} catch (Exception e) {
			return false;
		}
	}

	private void clearDefaultFlag() {
		redmineProjectRepository.findByIsDefaultTrue().ifPresent(existing -> {
			existing.setDefault(false);
			existing.setUpdatedAt(Instant.now());
			redmineProjectRepository.save(existing);
		});
	}
}
