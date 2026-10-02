package org.harbourcu.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldEncryptionTest {
    private final String key = Base64.getEncoder().encodeToString(new byte[32]);
    private final FieldEncryption fields = new FieldEncryption(key);

    @Test
    void decryptsUnicodeAndUsesFreshNonce() {
        byte[] first = fields.encrypt("Ama K. · Accra");
        byte[] second = fields.encrypt("Ama K. · Accra");
        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("Ama K.");
        assertThat(fields.decrypt(first)).isEqualTo("Ama K. · Accra");
        assertThat(fields.decrypt(second)).isEqualTo("Ama K. · Accra");
        assertThat(fields.encrypt(null)).isNull();
        assertThat(fields.decrypt(null)).isNull();
    }

    @Test
    void rejectsTamperingAndUnknownVersion() {
        byte[] encrypted = fields.encrypt("0200000001");
        encrypted[encrypted.length - 1] ^= 1;
        assertThatThrownBy(() -> fields.decrypt(encrypted))
            .isInstanceOf(IllegalArgumentException.class);
        encrypted[0] = 2;
        assertThatThrownBy(() -> fields.decrypt(encrypted))
            .hasMessageContaining("Unsupported");
    }

    @Test
    void indexesAreDeterministicAndPurposeSeparated() {
        String phone = fields.blindIndex("member-phone", "0200000001");
        assertThat(phone).isEqualTo(fields.blindIndex("member-phone", "0200000001"));
        assertThat(phone).isNotEqualTo(fields.blindIndex("member-id", "0200000001"));
        assertThat(phone).doesNotContain("0200000001");
    }

    @Test
    void requiresExactly256BitKey() {
        assertThatThrownBy(() -> new FieldEncryption("not-base64!"))
            .hasMessageContaining("base64");
        assertThatThrownBy(() -> new FieldEncryption(Base64.getEncoder().encodeToString(new byte[16])))
            .hasMessageContaining("32 bytes");
    }

    @Test
    void newKeyCanReadOldCiphertextAndSearchOldIndex() {
        byte[] nextKeyBytes = new byte[32];
        nextKeyBytes[0] = 1;
        String nextKey = Base64.getEncoder().encodeToString(nextKeyBytes);
        FieldEncryption rotated = new FieldEncryption(Map.of(1, key, 2, nextKey), 2);
        byte[] oldCiphertext = fields.encrypt("private value");
        assertThat(rotated.decrypt(oldCiphertext)).isEqualTo("private value");
        assertThat(rotated.encrypt("private value")[0]).isEqualTo((byte) 2);
        assertThat(rotated.blindIndexesForSearch("member-phone", "0200000001").get((byte) 1))
            .isEqualTo(fields.blindIndex("member-phone", "0200000001"));
    }
}
