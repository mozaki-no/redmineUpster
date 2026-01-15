package mozaki.redmineUpster.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mozaki.redmineUpster.domain.DiffEntity;
import mozaki.redmineUpster.domain.RedmineProjectEntity;
import mozaki.redmineUpster.domain.RunEntity;
import mozaki.redmineUpster.dto.RedmineProjectRequest;
import mozaki.redmineUpster.repository.DiffItemRepository;
import mozaki.redmineUpster.repository.DiffRepository;
import mozaki.redmineUpster.repository.IssueLinkRepository;
import mozaki.redmineUpster.repository.RedmineProjectRepository;
import mozaki.redmineUpster.repository.RunLogRepository;
import mozaki.redmineUpster.repository.RunRepository;
import mozaki.redmineUpster.repository.ScheduleRepository;

@Service
public class RedmineProjectService {
	private final RedmineProjectRepository redmineProjectRepository;
	private final RedmineClientFactory redmineClientFactory;
	private final DiffRepository diffRepository;
	private final DiffItemRepository diffItemRepository;
	private final RunRepository runRepository;
	private final RunLogRepository runLogRepository;
	private final ScheduleRepository scheduleRepository;
	private final IssueLinkRepository issueLinkRepository;

	public RedmineProjectService(RedmineProjectRepository redmineProjectRepository,
			RedmineClientFactory redmineClientFactory, DiffRepository diffRepository,
			DiffItemRepository diffItemRepository, RunRepository runRepository,
			RunLogRepository runLogRepository, ScheduleRepository scheduleRepository,
			IssueLinkRepository issueLinkRepository) {
		this.redmineProjectRepository = redmineProjectRepository;
		this.redmineClientFactory = redmineClientFactory;
		this.diffRepository = diffRepository;
		this.diffItemRepository = diffItemRepository;
		this.runRepository = runRepository;
		this.runLogRepository = runLogRepository;
		this.scheduleRepository = scheduleRepository;
		this.issueLinkRepository = issueLinkRepository;
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
		// スケジュールを削除
		scheduleRepository.deleteByRedmineProjectId(id);

		// 関連するDiffとその子データを削除
		for (DiffEntity diff : diffRepository.findByRedmineProjectId(id)) {
			// Runのログを削除
			for (RunEntity run : runRepository.findByDiffId(diff.getId())) {
				runLogRepository.deleteByRunId(run.getId());
			}
			// Runを削除
			runRepository.deleteAll(runRepository.findByDiffId(diff.getId()));
			// DiffItemを削除
			diffItemRepository.deleteByDiffId(diff.getId());
		}
		// Diffを削除
		diffRepository.deleteAll(diffRepository.findByRedmineProjectId(id));

		// IssueLinkを削除
		issueLinkRepository.deleteByRedmineProjectId(id);

		// プロジェクトを削除
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
