package org.harbourcu.storage;

import java.io.IOException;
import java.util.UUID;

/** Private object keys never contain member identities or original filenames. */
public interface ReceiptObjectStore {
  int MAX_BYTES = 10_485_760;

  void create(UUID key, byte[] bytes, String contentType) throws IOException;

  byte[] read(UUID key) throws IOException;

  void delete(UUID key) throws IOException;
}
