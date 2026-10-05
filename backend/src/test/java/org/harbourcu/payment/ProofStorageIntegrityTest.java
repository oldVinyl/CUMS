package org.harbourcu.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.harbourcu.role.PermissionService;
import org.harbourcu.storage.ReceiptObjectStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProofStorageIntegrityTest {
  @Test
  void authorizedDownloadsRejectChangedBytesAndSizeWithoutReturningEvidence() throws Exception {
    UUID actor = UUID.randomUUID(),
        id = UUID.randomUUID(),
        member = UUID.randomUUID(),
        key = UUID.randomUUID();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    PermissionService permissions = mock(PermissionService.class);
    ReceiptObjectStore store = mock(ReceiptObjectStore.class);
    when(permissions.hasPermission(actor, "PAYMENT_VERIFY")).thenReturn(true);
    String hash =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2}));
    when(jdbc.queryForList(anyString(), eq(id)))
        .thenReturn(
            List.of(
                Map.of(
                    "member_id",
                    member,
                    "storage_key",
                    key,
                    "content_type",
                    "image/png",
                    "sha256",
                    hash,
                    "byte_size",
                    2)));
    var service = new ProofStorage(store, jdbc, permissions, mock(ReceiptScanner.class));
    when(store.read(key)).thenReturn(new byte[] {1, 3});
    assertThatThrownBy(() -> service.download(actor, id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Receipt is temporarily unavailable.");
    when(store.read(key)).thenReturn(new byte[] {1});
    assertThatThrownBy(() -> service.download(actor, id)).isInstanceOf(IllegalStateException.class);
    when(store.read(key)).thenReturn(new byte[] {1, 2});
    assertThat(service.download(actor, id).bytes()).isEqualTo(new byte[] {1, 2});
  }

  @Test
  void unauthorizedDownloadsNeverContactStorage() throws Exception {
    UUID actor = UUID.randomUUID(), id = UUID.randomUUID();
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ReceiptObjectStore store = mock(ReceiptObjectStore.class);
    when(jdbc.queryForList(anyString(), eq(id)))
        .thenReturn(List.of(Map.of("member_id", UUID.randomUUID())));
    var service =
        new ProofStorage(store, jdbc, mock(PermissionService.class), mock(ReceiptScanner.class));
    assertThatThrownBy(() -> service.download(actor, id)).isInstanceOf(SecurityException.class);
    verifyNoInteractions(store);
  }
}
