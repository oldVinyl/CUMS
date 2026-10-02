package org.harbourcu.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    private final PasswordHasher passwords = new PasswordHasher(new PasswordPolicy());

    @Test
    void hashesWithArgon2idAndFreshSalt() {
        String password = "correct horse battery staple 2026";
        String first = passwords.hashNewPassword(password);
        String second = passwords.hashNewPassword(password);
        assertThat(first).startsWith("$argon2id$").isNotEqualTo(second);
        assertThat(passwords.verify(password, first)).isTrue();
        assertThat(passwords.verify("wrong password", first)).isFalse();
    }

    @Test
    void rejectsShortAndCommonPasswords() {
        assertThatThrownBy(() -> passwords.hashNewPassword("short"))
            .hasMessageContaining("12–128");
        assertThatThrownBy(() -> passwords.hashNewPassword("PASSWORD123456"))
            .hasMessageContaining("less common");
        assertThatThrownBy(() -> passwords.hashNewPassword("good password\nwith newline"))
            .hasMessageContaining("printable");
    }

    @Test
    void neverAcceptsMissingOrUnsupportedStoredHash() {
        assertThat(passwords.verify("correct horse battery staple 2026", null)).isFalse();
        assertThat(passwords.verify(null, "$argon2id$invalid")).isFalse();
        assertThat(passwords.verify("correct horse battery staple 2026", "{noop}password"))
            .isFalse();
    }
}
