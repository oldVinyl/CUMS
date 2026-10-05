package org.harbourcu.storage;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class ReceiptStorageConfiguration {
  @Bean
  ReceiptObjectStore receiptObjectStore(Environment env) {
    String driver = env.getProperty("APP_RECEIPT_STORAGE", "local");
    if (driver.equals("local"))
      return new LocalReceiptObjectStore(
          Path.of(env.getProperty("APP_UPLOAD_DIR", "./private-uploads")));
    if (!driver.equals("s3"))
      throw new IllegalArgumentException("Choose local or s3 receipt storage.");
    String endpoint = env.getProperty("APP_RECEIPT_S3_ENDPOINT", "");
    String encryption = env.getProperty("APP_RECEIPT_S3_ENCRYPTION", "AES256");
    boolean insecure = env.getProperty("APP_RECEIPT_S3_ALLOW_HTTP", Boolean.class, false);
    if (env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))
        && (insecure || !encryption.equals("AES256")))
      throw new IllegalArgumentException("Production receipt storage requires TLS and encryption.");
    var builder =
        S3Client.builder()
            .region(Region.of(env.getProperty("APP_RECEIPT_S3_REGION", "us-east-1")))
            .forcePathStyle(env.getProperty("APP_RECEIPT_S3_PATH_STYLE", Boolean.class, false))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)));
    if (!endpoint.isBlank()) {
      URI uri = URI.create(endpoint);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || (!uri.getScheme().equals("https") && !(insecure && uri.getScheme().equals("http"))))
        throw new IllegalArgumentException("Private receipt endpoint must use HTTPS.");
      builder.endpointOverride(uri);
    }
    return new S3ReceiptObjectStore(
        builder.build(),
        env.getProperty("APP_RECEIPT_S3_BUCKET", ""),
        env.getProperty("APP_RECEIPT_S3_PREFIX", "receipts"),
        encryption);
  }
}
