package org.harbourcu.storage;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ReceiptStorageConfigurationTest {
  @Test
  void defaultsToLocalAndRejectsUnknownDrivers() {
    var configuration = new ReceiptStorageConfiguration();
    assertThat(configuration.receiptObjectStore(new MockEnvironment()))
        .isInstanceOf(LocalReceiptObjectStore.class);
    assertThatThrownBy(
            () ->
                configuration.receiptObjectStore(
                    new MockEnvironment().withProperty("APP_RECEIPT_STORAGE", "typo")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void productionRejectsInsecureOverridesBeforeBuildingAClient() {
    var configuration = new ReceiptStorageConfiguration();
    var env =
        new MockEnvironment()
            .withProperty("APP_RECEIPT_STORAGE", "s3")
            .withProperty("APP_RECEIPT_S3_ALLOW_HTTP", "true");
    env.setActiveProfiles("prod");
    assertThatThrownBy(() -> configuration.receiptObjectStore(env))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Production receipt storage requires TLS and encryption.");
    env.setProperty("APP_RECEIPT_S3_ALLOW_HTTP", "false");
    env.setProperty("APP_RECEIPT_S3_ENCRYPTION", "NONE");
    assertThatThrownBy(() -> configuration.receiptObjectStore(env))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void endpointsRejectPlainHttpAndCredentialsInUrl() {
    var configuration = new ReceiptStorageConfiguration();
    var env =
        new MockEnvironment()
            .withProperty("APP_RECEIPT_STORAGE", "s3")
            .withProperty("APP_RECEIPT_S3_ENDPOINT", "http://storage.invalid");
    assertThatThrownBy(() -> configuration.receiptObjectStore(env))
        .isInstanceOf(IllegalArgumentException.class);
    env.setProperty("APP_RECEIPT_S3_ENDPOINT", "https://secret@storage.invalid");
    assertThatThrownBy(() -> configuration.receiptObjectStore(env))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
