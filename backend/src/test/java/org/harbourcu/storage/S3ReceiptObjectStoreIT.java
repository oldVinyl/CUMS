package org.harbourcu.storage;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3ReceiptObjectStoreIT {
  @Test
  void privateS3RoundTripCollisionAndMissingObject() throws Exception {
    try (var server =
        new GenericContainer<>("cums-storage-test:local")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/").forPort(9000))) {
      server.start();
      String endpoint = "http://" + server.getHost() + ":" + server.getMappedPort(9000);
      try (var client =
          S3Client.builder()
              .endpointOverride(URI.create(endpoint))
              .region(Region.US_EAST_1)
              .forcePathStyle(true)
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create("synthetic-root", "synthetic-password-only")))
              .build()) {
        String bucket = "synthetic-private-receipts";
        client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        var store = new S3ReceiptObjectStore(client, bucket, "receipts", "NONE");
        UUID key = UUID.randomUUID();
        byte[] bytes = {7, 8, 9};
        store.create(key, bytes, "image/png");
        assertThat(store.read(key)).isEqualTo(bytes);
        assertThatThrownBy(() -> store.create(key, new byte[] {4}, "image/png"))
            .isInstanceOf(IOException.class);
        assertThat(store.read(key)).isEqualTo(bytes);
        assertThat(
                client
                    .listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucket).prefix("receipts/").build())
                    .contents())
            .hasSize(1);
        try (var anonymous = java.net.http.HttpClient.newHttpClient()) {
          int status =
              anonymous
                  .send(
                      java.net.http.HttpRequest.newBuilder(
                              URI.create(endpoint + "/" + bucket + "/receipts/" + key))
                          .GET()
                          .build(),
                      java.net.http.HttpResponse.BodyHandlers.discarding())
                  .statusCode();
          assertThat(status).isEqualTo(403);
        }
        store.delete(key);
        store.delete(key);
        assertThatThrownBy(() -> store.read(key)).isInstanceOf(IOException.class);
      }
    }
  }
}
