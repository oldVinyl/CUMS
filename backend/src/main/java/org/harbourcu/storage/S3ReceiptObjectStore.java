package org.harbourcu.storage;

import java.io.IOException;
import java.util.UUID;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

public final class S3ReceiptObjectStore implements ReceiptObjectStore, AutoCloseable {
  private final S3Client client;
  private final String bucket, prefix, encryption;

  public S3ReceiptObjectStore(S3Client client, String bucket, String prefix, String encryption) {
    if (bucket == null
        || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        || prefix == null
        || !prefix.matches("[a-zA-Z0-9][a-zA-Z0-9/_-]*")
        || prefix.endsWith("/")
        || (!encryption.equals("AES256") && !encryption.equals("NONE")))
      throw new IllegalArgumentException("Invalid private receipt storage configuration.");
    this.client = client;
    this.bucket = bucket;
    this.prefix = prefix + "/";
    this.encryption = encryption;
  }

  public void create(UUID key, byte[] bytes, String type) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Receipt exceeds storage limit.");
    var request =
        PutObjectRequest.builder()
            .bucket(bucket)
            .key(prefix + key)
            .contentType(type)
            .ifNoneMatch("*");
    if (encryption.equals("AES256")) request.serverSideEncryption(ServerSideEncryption.AES256);
    try {
      client.putObject(request.build(), RequestBody.fromBytes(bytes));
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  public byte[] read(UUID key) throws IOException {
    try (var input =
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(prefix + key).build())) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw unavailable();
      return bytes;
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  public void delete(UUID key) throws IOException {
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(prefix + key).build());
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  public void close() {
    client.close();
  }

  private IOException unavailable() {
    return new IOException("Private receipt storage is unavailable.");
  }
}
