package com.vaultdrive.storage;

import com.sun.net.httpserver.HttpServer;
import com.vaultdrive.storage.config.S3StorageConfig;
import com.vaultdrive.storage.config.S3StorageProperties;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class S3DeletionTimeoutTest {
    @Test void stalledDeleteHasFiniteIoAndRetryableUncertainOutcome() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/", request -> {
            entered.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { request.close(); }
        });
        server.start();
        var properties = new S3StorageProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "garage", "local-fixture", "dummy-access", "dummy-secret");
        var binding = new StorageBinding(properties, "local-fixture");
        try (var client = new S3StorageConfig().s3Client(properties)) {
            var storage = new S3ObjectStorageService(client, properties, mock(FileStorageIdentityService.class), binding);
            long started = System.nanoTime();
            assertThatThrownBy(() -> storage.delete(binding.target("disposable")))
                    .isInstanceOf(StorageDeletionException.class).hasMessage("TRANSIENT");
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(12));
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
