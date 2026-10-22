package com.vaultdrive.storage;

import com.vaultdrive.storage.config.*;
import software.amazon.awssdk.services.s3.S3Client;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.vaultdrive.storage.GarageMultipartFixture.line;
import static org.assertj.core.api.Assertions.assertThat;

/** One real CopyObject request, upstream success fully consumed, downstream response lost. */
final class CopyDropProxy implements AutoCloseable {
    final ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
    final ExecutorService executor=Executors.newSingleThreadExecutor();
    final S3Client client;
    final Future<?> handler;
    CopyDropProxy(GarageMultipartFixture fixture) throws IOException {
        var p=fixture.properties; URI endpoint=URI.create(p.endpoint());
        assertThat(endpoint.getScheme()).isEqualTo("http");
        client=new S3StorageConfig().s3Client(new S3StorageProperties("http://localhost:"+server.getLocalPort(),p.region(),p.bucket(),p.accessKey(),p.secretKey()));
        handler=executor.submit(()->{
            try(Socket downstream=server.accept();Socket upstream=new Socket()) {
                downstream.setSoTimeout(10000); String request=line(downstream.getInputStream());
                assertThat(request).startsWith("PUT "); List<String> headers=new ArrayList<>(); boolean copy=false;
                for(String h;!(h=line(downstream.getInputStream())).isEmpty();) {
                    String lower=h.toLowerCase(Locale.ROOT);
                    if(lower.startsWith("x-amz-copy-source:"))copy=true;
                    if(lower.startsWith("content-length:"))assertThat(h.substring(h.indexOf(':')+1).trim()).isEqualTo("0");
                    if(!lower.startsWith("connection:"))headers.add(h);
                }
                assertThat(copy).isTrue();
                upstream.connect(new InetSocketAddress(endpoint.getHost(),endpoint.getPort()),2000); upstream.setSoTimeout(10000);
                upstream.getOutputStream().write((request+"\r\n"+String.join("\r\n",headers)+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                upstream.getOutputStream().flush(); String wire=new String(upstream.getInputStream().readNBytes(65536),StandardCharsets.UTF_8);
                assertThat(wire.startsWith("HTTP/1.1 200") && wire.contains("CopyObjectResult") && !wire.contains("<Error>")).isTrue();
                fixture.event("COPY_UPSTREAM_ACK_DROPPED","http=200 valid_result=true requests=1");
            }catch(Exception e){throw new IllegalStateException("Copy proxy failed: "+e.getClass().getSimpleName());}
        });
    }
    void verifyAccepted() throws Exception {handler.get(15,TimeUnit.SECONDS);}
    @Override public void close() throws Exception {
        client.close();server.close();executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();
    }
}
