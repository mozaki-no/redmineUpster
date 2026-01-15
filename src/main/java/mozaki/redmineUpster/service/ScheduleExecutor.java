package mozaki.redmineUpster.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import mozaki.redmineUpster.domain.ScheduleEntity;
import mozaki.redmineUpster.repository.ScheduleRepository;

@Component
public class ScheduleExecutor {
	private static final Logger log = LoggerFactory.getLogger(ScheduleExecutor.class);

	private final ScheduleRepository scheduleRepository;
	private final ScheduleService scheduleService;
	private final ThreadPoolTaskScheduler taskScheduler;
	private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

	public ScheduleExecutor(ScheduleRepository scheduleRepository, ScheduleService scheduleService,
			ThreadPoolTaskScheduler taskScheduler) {
		this.scheduleRepository = scheduleRepository;
		this.scheduleService = scheduleService;
		this.taskScheduler = taskScheduler;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void initSchedules() {
		log.info("Initializing scheduled tasks...");
		for (ScheduleEntity schedule : scheduleRepository.findByEnabledTrue()) {
			scheduleTask(schedule);
		}
		log.info("Initialized {} scheduled tasks", scheduledTasks.size());
	}

	public void scheduleTask(ScheduleEntity schedule) {
		if (!schedule.isEnabled()) {
			cancelTask(schedule.getId());
			return;
		}

		cancelTask(schedule.getId());

		try {
			CronTrigger trigger = new CronTrigger(schedule.getCronExpression());
			ScheduledFuture<?> future = taskScheduler.schedule(
					() -> executeSchedule(schedule.getId()),
					trigger);
			scheduledTasks.put(schedule.getId(), future);
			log.info("Scheduled task {} with cron: {}", schedule.getId(), schedule.getCronExpression());
		} catch (Exception e) {
			log.error("Failed to schedule task {}: {}", schedule.getId(), e.getMessage());
		}
	}

	public void cancelTask(Long scheduleId) {
		ScheduledFuture<?> future = scheduledTasks.remove(scheduleId);
		if (future != null) {
			future.cancel(false);
			log.info("Cancelled scheduled task {}", scheduleId);
		}
	}

	public void reschedule(ScheduleEntity schedule) {
		cancelTask(schedule.getId());
		if (schedule.isEnabled()) {
			scheduleTask(schedule);
		}
	}

	private void executeSchedule(Long scheduleId) {
		log.info("Executing scheduled task {}", scheduleId);
		try {
			scheduleRepository.findById(scheduleId).ifPresent(schedule -> {
				if (!schedule.isEnabled()) {
					log.info("Schedule {} is disabled, skipping execution", scheduleId);
					return;
				}

				// WBSファイルパスが設定されている場合のみ実行
				// 現時点ではファイルからの自動読み込みは未実装のため、ログのみ出力
				log.info("Schedule {} executed: project={}, wbsFile={}, dryRun={}",
						scheduleId,
						schedule.getRedmineProject().getName(),
						schedule.getWbsFilePath(),
						schedule.isDryRun());

				// 最終実行時刻を更新
				scheduleService.updateLastRun(scheduleId);
			});
		} catch (Exception e) {
			log.error("Failed to execute scheduled task {}: {}", scheduleId, e.getMessage());
		}
	}
}
