package com.vaultdrive.storage.config;

import java.net.URI;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import java.time.Duration;

import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;

@Configuration
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {

    @Bean
    public S3Presigner s3Presigner(S3StorageProperties properties) {
        return S3Presigner.builder().endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    @Bean
    public S3Client s3Client(S3StorageProperties properties) {

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                properties.accessKey(),
                properties.secretKey()
        );

        return S3Client.builder()
                .endpointOverride(URI.create(properties.endpoint()))
                .region(Region.of(properties.region()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(credentials)
                )
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(5)))
                // Uncertain multipart control calls must never dispatch a second attempt.
                // Purge retries belong to the durable PostgreSQL job lifecycle.
                .overrideConfiguration(config -> config.retryStrategy(
                        StandardRetryStrategy.builder().maxAttempts(1).build()))
                .requestChecksumCalculation(
                        RequestChecksumCalculation.WHEN_REQUIRED
                )
                .responseChecksumValidation(
                        ResponseChecksumValidation.WHEN_REQUIRED
                )
                .serviceConfiguration(
                        S3Configuration.builder()
                                .pathStyleAccessEnabled(true)
                                .build()
                )
                .build();
    }
}
