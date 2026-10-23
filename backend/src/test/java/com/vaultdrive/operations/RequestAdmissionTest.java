package com.vaultdrive.operations;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.AsyncEvent;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static com.vaultdrive.operations.RequestAdmission.Lane.*;

class RequestAdmissionTest {
    AdmissionProperties properties(int concurrency,int burst) {
        var p=new AdmissionProperties.Policy(concurrency,burst,1);
        return new AdmissionProperties(p,p,p,p);
    }
    @Test void rateRefillsOnMonotonicTimeAndLanesAreIndependent() {
        var time=new AtomicLong(); var admission=new RequestAdmission(properties(1,2),time::get);
        admission.acquire(AUTHENTICATION).close(); admission.acquire(AUTHENTICATION).close();
        assertThat(admission.acquire(AUTHENTICATION)).isNull();
        admission.acquire(MULTIPART).close();
        time.addAndGet(999_000_000); assertThat(admission.acquire(AUTHENTICATION)).isNull();
        time.addAndGet(1_000_000); admission.acquire(AUTHENTICATION).close();
        assertThat(admission.counts().get(AUTHENTICATION).rejected()).isEqualTo(2);
    }
    @Test void competingRequestsCannotExceedCapacityAndRepeatedCloseIsSafe() throws Exception {
        var admission=new RequestAdmission(properties(2,100)); var start=new CountDownLatch(1);
        var release=new CountDownLatch(1); var acquired=new AtomicInteger(); var rejected=new AtomicInteger();
        var workers=Executors.newFixedThreadPool(10); var observed=new CountDownLatch(10);
        try {
            var results=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<10;i++) results.add(workers.submit(()->{
                try { start.await(); var p=admission.acquire(DOWNLOADS);
                    if(p==null){rejected.incrementAndGet();observed.countDown();return;}
                    acquired.incrementAndGet();observed.countDown();
                    try {release.await();} finally {p.close();p.close();}
                } catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
            start.countDown(); assertThat(observed.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(acquired).hasValue(2); assertThat(rejected).hasValue(8);
            release.countDown(); for(var result:results) result.get(5,TimeUnit.SECONDS);
            assertThat(admission.counts().get(DOWNLOADS).active()).isZero();
        } finally {release.countDown();workers.shutdownNow();}
    }
    @Test void filterReturnsCommon429WithoutEnteringRejectedHandlerAndReleasesErrors() throws Exception {
        var admission=new RequestAdmission(properties(1,100)); var filter=new AdmissionFilter(admission);
        var held=admission.acquire(METADATA); var response=new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET","/api/v1/search"),response,(q,s)->{throw new AssertionError();});
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("TOO_MANY_REQUESTS"); held.close();
        assertThatThrownBy(()->filter.doFilter(new MockHttpServletRequest("GET","/api/v1/search"),new MockHttpServletResponse(),
                (q,s)->{throw new IOException("fixture");})).isInstanceOf(IOException.class);
        assertThat(admission.counts().get(METADATA).active()).isZero();
    }
    @Test void timeoutBeforeStreamStartsClosesStorageAndReturnsPermit() throws Exception {
        var admission=new RequestAdmission(properties(1,100)); var req=new MockHttpServletRequest("GET","/api/v1/files/id/download");
        req.setAsyncSupported(true);var closed=new AtomicBoolean();
        var input=new ByteArrayInputStream(new byte[]{1}){public void close(){closed.set(true);}};
        var body=new AtomicReference<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>();
        new AdmissionFilter(admission).doFilter(req,new MockHttpServletResponse(),(q,s)->{
            body.set(AdmissionLease.streaming(req,input));req.startAsync();
        });
        assertThat(admission.counts().get(DOWNLOADS).active()).isEqualTo(1);
        var async=(MockAsyncContext)req.getAsyncContext();
        for(var listener:async.getListeners())listener.onTimeout(new AsyncEvent(async));
        assertThat(closed).isTrue(); assertThat(admission.counts().get(DOWNLOADS).active()).isZero();
        var bytes=new ByteArrayOutputStream();body.get().writeTo(bytes);assertThat(bytes.size()).isZero();
        for(var listener:async.getListeners())listener.onComplete(new AsyncEvent(async));
        assertThat(admission.counts().get(DOWNLOADS).active()).isZero();
    }
    @Test void cancellationRetainsCapacityUntilUncooperativeStreamActuallyExits() throws Exception {
        var admission=new RequestAdmission(properties(1,100));var lease=new AdmissionLease(admission.acquire(DOWNLOADS));
        var req=new MockHttpServletRequest();req.setAttribute(AdmissionLease.ATTRIBUTE,lease);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new AtomicBoolean();
        var input=new InputStream(){public int read() throws IOException {
            entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException();}catch(InterruptedException e){throw new IOException(e);}return -1;
        } public void close(){closed.set(true);}};
        var stream=AdmissionLease.streaming(req,input);var executor=Executors.newSingleThreadExecutor();
        try {
            var future=executor.submit(()->{try{stream.writeTo(OutputStream.nullOutputStream());}catch(IOException e){throw new RuntimeException(e);}});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();lease.finish();
            assertThat(closed).isTrue();assertThat(admission.acquire(DOWNLOADS)).isNull();
            release.countDown();future.get(5,TimeUnit.SECONDS);
            assertThat(admission.counts().get(DOWNLOADS).active()).isZero();
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void streamFailureAndNormalCompletionReleaseAfterRequestCompletion() throws Exception {
        for(boolean fail:new boolean[]{false,true}){
            var admission=new RequestAdmission(properties(1,100));var lease=new AdmissionLease(admission.acquire(DOWNLOADS));
            var req=new MockHttpServletRequest();req.setAttribute(AdmissionLease.ATTRIBUTE,lease);
            var stream=AdmissionLease.streaming(req,new ByteArrayInputStream(new byte[]{1,2}));
            try{stream.writeTo(fail?new OutputStream(){public void write(int i)throws IOException{throw new IOException();}}:OutputStream.nullOutputStream());}
            catch(IOException expected){assertThat(fail).isTrue();}
            lease.finish();assertThat(admission.counts().get(DOWNLOADS).active()).isZero();
        }
    }
    @Test void routingCoversExpensiveMutationsAndExcludesHealth() {
        assertThat(AdmissionFilter.lane("/api/v1/auth/refresh")).isEqualTo(AUTHENTICATION);
        assertThat(AdmissionFilter.lane("/api/v1/upload-sessions/id/part-urls")).isEqualTo(MULTIPART);
        assertThat(AdmissionFilter.lane("/api/v1/folders/id/copy")).isEqualTo(METADATA);
        assertThat(AdmissionFilter.lane("/api/v1/health/ready")).isNull();
    }
    @Test void uncheckedStorageCloseFailureCannotLeakAnUnstartedStreamPermit() {
        var admission=new RequestAdmission(properties(1,10));var lease=new AdmissionLease(admission.acquire(DOWNLOADS));
        var request=new MockHttpServletRequest();request.setAttribute(AdmissionLease.ATTRIBUTE,lease);
        AdmissionLease.streaming(request,new ByteArrayInputStream(new byte[0]){public void close(){throw new IllegalStateException();}});
        lease.finish();assertThat(admission.counts().get(DOWNLOADS).active()).isZero();
    }
}
