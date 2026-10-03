package org.harbourcu.payment;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ReceiptScannerTest {
    @Test void sendsBoundedInstreamChunksAndAcceptsOnlyCleanVerdict() throws Exception {
        byte[] bytes = new byte[150000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        exercise(bytes, "stream: OK\0", scanner -> assertThatCode(() -> scanner.requireClean(bytes)).doesNotThrowAnyException());
    }

    @Test void rejectsMalwareAndUnavailableOrMalformedDaemon() throws Exception {
        byte[] bytes = "synthetic-fixture".getBytes(StandardCharsets.US_ASCII);
        exercise(bytes, "stream: SyntheticFixture FOUND\0", scanner -> assertThatThrownBy(() -> scanner.requireClean(bytes))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("SyntheticFixture"));
        exercise(bytes, "daemon error\0", scanner -> assertThatThrownBy(() -> scanner.requireClean(bytes)).isInstanceOf(IllegalStateException.class));
        exercise(bytes, "stream: OK", scanner -> assertThatThrownBy(() -> scanner.requireClean(bytes)).isInstanceOf(IllegalStateException.class));
        int unused;
        try (var server = new ServerSocket(0)) { unused = server.getLocalPort(); }
        assertThatThrownBy(() -> new ReceiptScanner("127.0.0.1", unused, 500).requireClean(bytes)).isInstanceOf(IllegalStateException.class);
    }

    @Test void unconfiguredScannerStillEnforcesInputLimits() {
        var scanner = new ReceiptScanner("",3310,500);
        assertThatCode(() -> scanner.requireClean(new byte[]{1})).doesNotThrowAnyException();
        assertThatThrownBy(() -> scanner.requireClean(new byte[10485761])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scanner.requireClean(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    private void exercise(byte[] expected, String reply, java.util.function.Consumer<ReceiptScanner> check) throws Exception {
        try (var server = new ServerSocket(0)) {
            var received = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    var input = new DataInputStream(socket.getInputStream());
                    assertThat(new String(input.readNBytes(10),StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                    var all = new ByteArrayOutputStream();
                    int size;
                    while ((size = input.readInt()) != 0) {
                        assertThat(size).isBetween(1,65536); all.write(input.readNBytes(size));
                    }
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                    return all.toByteArray();
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            check.accept(new ReceiptScanner("127.0.0.1",server.getLocalPort(),1000));
            assertThat(received.get(5,TimeUnit.SECONDS)).isEqualTo(expected);
        }
    }
}
