package org.fabt.shared.config;

import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * Configures virtual thread support for scheduling and task execution.
 *
 * With {@code spring.threads.virtual.enabled=true}, Tomcat and @Async already use
 * virtual threads. This config ensures @Scheduled tasks and BatchJobScheduler also
 * run on virtual threads instead of the default single-thread platform pool.
 */
@Configuration
public class VirtualThreadConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        SimpleAsyncTaskScheduler scheduler = new SimpleAsyncTaskScheduler();
        scheduler.setVirtualThreads(true);
        scheduler.setThreadNamePrefix("vt-scheduler-");
        scheduler.setTaskTerminationTimeout(30_000);
        return scheduler;
    }
}
