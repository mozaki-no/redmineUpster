package mozaki.redmineUpster.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import mozaki.redmineUpster.domain.RedmineProjectEntity;
import mozaki.redmineUpster.domain.ScheduleEntity;
import mozaki.redmineUpster.dto.ScheduleRequest;
import mozaki.redmineUpster.repository.RedmineProjectRepository;
import mozaki.redmineUpster.repository.ScheduleRepository;

@Service
public class ScheduleService {
	private final ScheduleRepository scheduleRepository;
	private final RedmineProjectRepository redmineProjectRepository;

	public ScheduleService(ScheduleRepository scheduleRepository, RedmineProjectRepository redmineProjectRepository) {
		this.scheduleRepository = scheduleRepository;
		this.redmineProjectRepository = redmineProjectRepository;
	}

	public List<ScheduleEntity> findAll() {
		return scheduleRepository.findAll();
	}

	public List<ScheduleEntity> findEnabled() {
		return scheduleRepository.findByEnabledTrue();
	}

	public Optional<ScheduleEntity> findById(Long id) {
		return scheduleRepository.findById(id);
	}

	@Transactional
	public ScheduleEntity create(ScheduleRequest request) {
		validateCronExpression(request.getCronExpression());

		RedmineProjectEntity project = redmineProjectRepository.findById(request.getRedmineProjectId())
				.orElseThrow(() -> new IllegalArgumentException("RedmineProject not found: " + request.getRedmineProjectId()));

		ScheduleEntity entity = new ScheduleEntity(request.getName(), request.getCronExpression(), project);
		entity.setWbsFilePath(request.getWbsFilePath());
		entity.setDryRun(request.isDryRun());
		entity.setEnabled(request.isEnabled());
		entity.setNextRunAt(calculateNextRun(request.getCronExpression()));

		return scheduleRepository.save(entity);
	}

	@Transactional
	public ScheduleEntity update(Long id, ScheduleRequest request) {
		validateCronExpression(request.getCronExpression());

		ScheduleEntity entity = scheduleRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));

		RedmineProjectEntity project = redmineProjectRepository.findById(request.getRedmineProjectId())
				.orElseThrow(() -> new IllegalArgumentException("RedmineProject not found: " + request.getRedmineProjectId()));

		entity.setName(request.getName());
		entity.setCronExpression(request.getCronExpression());
		entity.setRedmineProject(project);
		entity.setWbsFilePath(request.getWbsFilePath());
		entity.setDryRun(request.isDryRun());
		entity.setEnabled(request.isEnabled());
		entity.setNextRunAt(calculateNextRun(request.getCronExpression()));

		return scheduleRepository.save(entity);
	}

	@Transactional
	public void delete(Long id) {
		scheduleRepository.deleteById(id);
	}

	@Transactional
	public ScheduleEntity toggle(Long id) {
		ScheduleEntity entity = scheduleRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Schedule not found: " + id));
		entity.setEnabled(!entity.isEnabled());
		if (entity.isEnabled()) {
			entity.setNextRunAt(calculateNextRun(entity.getCronExpression()));
		}
		return scheduleRepository.save(entity);
	}

	@Transactional
	public void updateLastRun(Long id) {
		scheduleRepository.findById(id).ifPresent(entity -> {
			entity.setLastRunAt(Instant.now());
			entity.setNextRunAt(calculateNextRun(entity.getCronExpression()));
			scheduleRepository.save(entity);
		});
	}

	private void validateCronExpression(String expression) {
		try {
			CronExpression.parse(expression);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid cron expression: " + expression);
		}
	}

	private Instant calculateNextRun(String cronExpression) {
		try {
			CronExpression cron = CronExpression.parse(cronExpression);
			return cron.next(java.time.LocalDateTime.now())
					.atZone(java.time.ZoneId.systemDefault())
					.toInstant();
		} catch (Exception e) {
			return null;
		}
	}
}
