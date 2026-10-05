package org.harbourcu.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

public final class LocalReceiptObjectStore implements ReceiptObjectStore {
  private final Path root;

  public LocalReceiptObjectStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  public void create(UUID key, byte[] bytes, String type) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Receipt exceeds storage limit.");
    Files.createDirectories(root);
    Files.write(root.resolve(key.toString()), bytes, StandardOpenOption.CREATE_NEW);
  }

  public byte[] read(UUID key) throws IOException {
    try (var input = Files.newInputStream(root.resolve(key.toString()))) {
      byte[] bytes = input.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw new IOException("Receipt exceeds storage limit.");
      return bytes;
    }
  }

  public void delete(UUID key) throws IOException {
    Files.deleteIfExists(root.resolve(key.toString()));
  }
}
