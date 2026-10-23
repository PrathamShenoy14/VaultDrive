package com.vaultdrive.operations;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Request completion and actual stream exit must both happen before capacity is reusable. */
public final class AdmissionLease {
    static final String ATTRIBUTE = AdmissionLease.class.getName();
    private final RequestAdmission.Permit permit;
    private boolean requestDone;
    private Stream stream;
    AdmissionLease(RequestAdmission.Permit permit) { this.permit = permit; }

    public static StreamingResponseBody streaming(HttpServletRequest request, InputStream input) {
        var lease = (AdmissionLease) request.getAttribute(ATTRIBUTE);
        if (lease == null) return output -> { try (input) { input.transferTo(output); } };
        synchronized (lease) {
            lease.stream = lease.new Stream(input);
            return lease.stream;
        }
    }
    synchronized void finish() {
        requestDone = true;
        try { if (stream != null) stream.cancel(); }
        finally { releaseIfFinished(); }
    }
    private void releaseIfFinished() {
        if (requestDone && (stream == null || stream.finished)) permit.close();
    }
    private final class Stream implements StreamingResponseBody {
        final InputStream input;
        boolean running, finished;
        Stream(InputStream input) { this.input = input; }
        public void writeTo(java.io.OutputStream output) throws IOException {
            synchronized (AdmissionLease.this) {
                if (finished) return;
                running = true;
            }
            try (input) { input.transferTo(output); }
            finally {
                synchronized (AdmissionLease.this) { finished = true; releaseIfFinished(); }
            }
        }
        void cancel() {
            // Close the storage stream on timeout/rejection even if its callback never began.
            try { input.close(); } catch (IOException | RuntimeException ignored) { }
            finally { if (!running) finished = true; }
        }
    }
}
