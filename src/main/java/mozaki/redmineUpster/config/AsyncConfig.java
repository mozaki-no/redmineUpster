package mozaki.redmineUpster.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 非同期処理の設定クラス。
 * <p>
 * タスク実行用とスケジューリング用のスレッドプールを構成します。
 * </p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

	/** タスク実行スレッドプールのコアサイズ */
	private static final int EXECUTOR_CORE_POOL_SIZE = 4;
	/** タスク実行スレッドプールの最大サイズ */
	private static final int EXECUTOR_MAX_POOL_SIZE = 8;
	/** タスク実行キューの容量 */
	private static final int EXECUTOR_QUEUE_CAPACITY = 100;
	/** スケジューラースレッドプールのサイズ */
	private static final int SCHEDULER_POOL_SIZE = 4;

	/**
	 * タスク実行用のスレッドプールを構成します。
	 *
	 * @return タスク実行用Executor
	 */
	@Bean(name = "taskExecutor")
	public Executor taskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(EXECUTOR_CORE_POOL_SIZE);
		executor.setMaxPoolSize(EXECUTOR_MAX_POOL_SIZE);
		executor.setQueueCapacity(EXECUTOR_QUEUE_CAPACITY);
		executor.setThreadNamePrefix("RedmineSync-");
		executor.initialize();
		return executor;
	}

	/**
	 * スケジューリング用のスレッドプールを構成します。
	 *
	 * @return スケジューリング用ThreadPoolTaskScheduler
	 */
	@Bean
	public ThreadPoolTaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
		scheduler.setThreadNamePrefix("ScheduledTask-");
		scheduler.initialize();
		return scheduler;
	}
}
