package com.vaultdrive.worker;

import com.vaultdrive.job.BackgroundJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JobWorkerProperties.class)
public class JobWorkerConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "vaultdrive.jobs.worker.enabled", havingValue = "true")
    static class RuntimeConfiguration {
        @Bean(initMethod = "start", destroyMethod = "close")
        JobWorker jobWorker(BackgroundJobService jobs, List<JobHandler> handlers, JobWorkerProperties properties) {
            return new JobWorker(jobs, handlers, properties);
        }

        @Bean
        ApplicationListener<ContextClosedEvent> jobWorkerCloseListener(JobWorker worker) {
            // Close event precedes destruction; stop admission before other
            // components start shutting down. Never wait inside this callback.
            return event -> worker.stopClaiming();
        }
    }
}
