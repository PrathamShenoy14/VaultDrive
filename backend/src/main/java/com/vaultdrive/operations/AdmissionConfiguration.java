package com.vaultdrive.operations;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionProperties.class)
public class AdmissionConfiguration {
    @Bean
    ThreadPoolTaskExecutor downloadExecutor(AdmissionProperties p) {
        var executor = new ThreadPoolTaskExecutor();
        int max = p.downloads().concurrency() + 1; // One bounded handoff slot; public downloads stream synchronously.
        executor.setCorePoolSize(max); executor.setMaxPoolSize(max); executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("download-"); executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(false); executor.setAwaitTerminationSeconds(2);
        return executor;
    }
    @Bean
    WebMvcConfigurer boundedStreaming(ThreadPoolTaskExecutor downloadExecutor) {
        return new WebMvcConfigurer() {
            public void configureAsyncSupport(AsyncSupportConfigurer config) {
                config.setTaskExecutor(downloadExecutor); config.setDefaultTimeout(300_000);
            }
        };
    }
}
