package org.harbourcu.payment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional ClamAV INSTREAM integration; configured scans always fail closed. */
@Component
public class ReceiptScanner {
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
        Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "receipt-scan-deadline"); thread.setDaemon(true); return thread;
        });
    private final String host;
    private final int port;
    private final int timeout;

    public ReceiptScanner(@Value("${CLAMAV_HOST:}") String host,
                          @Value("${CLAMAV_PORT:3310}") int port,
                          @Value("${CLAMAV_TIMEOUT_MS:5000}") int timeout) {
        this.host = host.trim(); this.port = port; this.timeout = timeout;
        if (port < 1 || port > 65535 || timeout < 100 || timeout > 30000) {
            throw new IllegalArgumentException("Invalid receipt scanner configuration.");
        }
    }

    public void requireClean(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 10485760) {
            throw new IllegalArgumentException("Receipt must contain at most 10 MB.");
        }
        if (host.isEmpty()) return; // Explicitly unconfigured development/local installation.
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            var deadline = DEADLINES.schedule(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            }, timeout, TimeUnit.MILLISECONDS);
            try {
            var output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < bytes.length; offset += 65536) {
                int size = Math.min(65536, bytes.length - offset);
                output.writeInt(size); output.write(bytes, offset, size);
            }
            output.writeInt(0); output.flush();
            var response = new ByteArrayOutputStream();
            int value;
            while ((value = socket.getInputStream().read()) != 0) {
                if (value < 0 || response.size() >= 1024) throw new IOException("Invalid scanner response");
                response.write(value);
            }
            String verdict = response.toString(StandardCharsets.US_ASCII).trim();
            if ("stream: OK".equals(verdict)) return;
            if (verdict.startsWith("stream: ") && verdict.endsWith(" FOUND")) {
                throw new IllegalArgumentException("This receipt did not pass the security scan. Choose a safe file.");
            }
            throw new IOException("Scanner did not return a clean verdict");
            } finally { deadline.cancel(false); }
        } catch (IOException unavailable) {
            // Do not expose file content, daemon response, host or signature information.
            throw new IllegalStateException("Receipt security scanning is unavailable. Please try again later.");
        }
    }
}
