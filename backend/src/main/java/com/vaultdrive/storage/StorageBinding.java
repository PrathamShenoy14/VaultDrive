package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class StorageBinding {
    private final String namespace;
    private final String endpoint;
    private final String region;
    private final String bucket;

    public StorageBinding(S3StorageProperties properties,
                          @Value("${storage.s3.namespace:garage-default}") String namespace) {
        URI uri = URI.create(properties.endpoint()).normalize();
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid storage endpoint");
        }
        this.namespace = namespace;
        this.endpoint = uri.toString();
        this.region = properties.region();
        this.bucket = properties.bucket();
        target("validation");
    }

    public StorageTarget target(String key) {
        return new StorageTarget(namespace, endpoint, region, bucket, key);
    }

    public boolean matches(StorageTarget target) { return target(target.key()).equals(target); }
}
