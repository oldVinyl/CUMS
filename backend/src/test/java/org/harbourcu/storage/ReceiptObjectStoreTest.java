package org.harbourcu.storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class ReceiptObjectStoreTest {
  @TempDir Path directory;

  @Test
  void localCreateReadAndDeleteAreBoundedAndDoNotOverwrite() throws Exception {
    var store = new LocalReceiptObjectStore(directory);
    UUID key = UUID.randomUUID();
    byte[] receipt = {1, 2, 3};
    store.create(key, receipt, "image/png");
    assertThat(store.read(key)).isEqualTo(receipt);
    assertThatThrownBy(() -> store.create(key, new byte[] {4}, "image/png"))
        .isInstanceOf(IOException.class);
    assertThat(store.read(key)).isEqualTo(receipt);
    store.delete(key);
    store.delete(key);
    assertThatThrownBy(() -> store.read(key)).isInstanceOf(IOException.class);
    assertThatThrownBy(
            () -> store.create(key, new byte[ReceiptObjectStore.MAX_BYTES + 1], "image/png"))
        .isInstanceOf(IOException.class);
    Files.write(directory.resolve(key.toString()), new byte[ReceiptObjectStore.MAX_BYTES + 1]);
    assertThatThrownBy(() -> store.read(key)).isInstanceOf(IOException.class);
  }

  @Test
  void s3UsesAnonymousPrivateConditionalWritesAndGenericErrors() throws Exception {
    S3Client client = mock(S3Client.class);
    UUID key = UUID.randomUUID();
    var store = new S3ReceiptObjectStore(client, "synthetic-private-bucket", "receipts", "AES256");
    store.create(key, new byte[] {1, 2}, "image/png");
    var request = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(client).putObject(request.capture(), any(RequestBody.class));
    assertThat(request.getValue().key()).isEqualTo("receipts/" + key);
    assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
    assertThat(request.getValue().acl()).isNull();
    assertThat(request.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(new IllegalStateException("PRIVATE_BUCKET_AND_SECRET"));
    assertThatThrownBy(() -> store.create(key, new byte[] {1}, "image/png"))
        .isInstanceOf(IOException.class)
        .hasMessage("Private receipt storage is unavailable.")
        .hasNoCause();
    assertThatThrownBy(
            () ->
                new S3ReceiptObjectStore(client, "synthetic-private-bucket", "../private", "NONE"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
