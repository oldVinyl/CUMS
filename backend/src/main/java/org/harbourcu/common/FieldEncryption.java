package org.harbourcu.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypts sensitive member fields and produces purpose-scoped exact-match indexes. */
public final class FieldEncryption {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final Map<Byte, SecretKeySpec> encryptionKeys;
    private final byte currentVersion;
    private final Map<Byte, SecretKeySpec> indexKeys;
    private final SecureRandom random;

    public FieldEncryption(String base64Key) {
        this(Map.of(1, base64Key), 1);
    }

    public FieldEncryption(Map<Integer, String> versionedKeys, int currentVersion) {
        if (currentVersion < 1 || currentVersion > 255 || !versionedKeys.containsKey(currentVersion)) {
            throw new IllegalArgumentException("Current encryption key version is missing");
        }
        this.encryptionKeys = versionedKeys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            entry -> {
                if (entry.getKey() < 1 || entry.getKey() > 255) {
                    throw new IllegalArgumentException("Encryption key version must be between 1 and 255");
                }
                return entry.getKey().byteValue();
            },
            entry -> new SecretKeySpec(decodeKey(entry.getValue()), "AES")));
        this.currentVersion = (byte) currentVersion;
        this.indexKeys = versionedKeys.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            entry -> entry.getKey().byteValue(),
            entry -> new SecretKeySpec(hmac(decodeKey(entry.getValue()),
                "blind-index-key:v1"), "HmacSHA256")));
        this.random = new SecureRandom();
    }

    private static byte[] decodeKey(String base64Key) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("APP_ENCRYPTION_KEY must be base64 encoded", e);
        }
        if (raw.length != 32) {
            throw new IllegalArgumentException("APP_ENCRYPTION_KEY must decode to 32 bytes");
        }
        return raw;
    }

    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKeys.get(currentVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(new byte[] {currentVersion});
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE_BYTES + encrypted.length)
                .put(currentVersion).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        if (ciphertext.length < 1 + NONCE_BYTES + 16 || !encryptionKeys.containsKey(ciphertext[0])) {
            throw new IllegalArgumentException("Unsupported or malformed encrypted field");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKeys.get(ciphertext[0]),
                new GCMParameterSpec(TAG_BITS, ciphertext, 1, NONCE_BYTES));
            cipher.updateAAD(new byte[] {ciphertext[0]});
            byte[] clear = cipher.doFinal(ciphertext, 1 + NONCE_BYTES,
                ciphertext.length - 1 - NONCE_BYTES);
            return new String(clear, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Encrypted field authentication failed", e);
        }
    }

    public String blindIndex(String purpose, String normalizedValue) {
        return blindIndex(currentVersion, purpose, normalizedValue);
    }

    public Map<Byte, String> blindIndexesForSearch(String purpose, String normalizedValue) {
        return indexKeys.keySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            version -> version, version -> blindIndex(version, purpose, normalizedValue)));
    }

    private String blindIndex(byte version, String purpose, String normalizedValue) {
        if (purpose == null || purpose.isBlank() || normalizedValue == null || normalizedValue.isBlank()) {
            throw new IllegalArgumentException("Purpose and normalized value are required");
        }
        byte[] message = (purpose + "\0" + normalizedValue).getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indexKeys.get(version));
            return "v" + Byte.toUnsignedInt(version) + ":" + HexFormat.of().formatHex(mac.doFinal(message));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Blind index calculation failed", e);
        }
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Key derivation failed", e);
        }
    }
}
