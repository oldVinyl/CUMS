package org.harbourcu.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class MemberServiceIntegrationTest {
    @Autowired MemberService members;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Transactional
    void createsEncryptedMemberAndAuditsPrivateRead() {
        UUID officer = userWithRole("TREASURER");
        UUID id = members.create(officer, fixture(number()));
        byte[] encryptedPhone = jdbc.queryForObject(
            "SELECT encrypted_phone FROM members WHERE id = ?", byte[].class, id);
        assertThat(new String(encryptedPhone, java.nio.charset.StandardCharsets.UTF_8))
            .doesNotContain("0201234001");
        assertThat(jdbc.queryForObject("SELECT phone_blind_index FROM members WHERE id = ?",
            String.class, id)).startsWith("v1:").doesNotContain("0201234001");
        MemberService.Detail detail = members.get(officer, id, "test-request");
        assertThat(detail.phone()).isEqualTo("0201234001");
        assertThat(detail.idNumber()).isEqualTo("GHA-123-456");
        assertThat(detail.nextOfKinName()).isEqualTo("Ada Example");
        assertThat(members.list(officer)).anyMatch(item -> item.id().equals(id));
        assertThat(jdbc.queryForList("""
            SELECT action FROM audit_log WHERE entity_id = ? ORDER BY occurred_at
            """, String.class, id)).containsExactlyInAnyOrder("MEMBER_CREATED", "SENSITIVE_MEMBER_VIEW");
    }

    @Test
    @Transactional
    void memberRoleCannotViewOrCreateUnionMemberRecords() {
        UUID memberUser = userWithRole("MEMBER");
        assertThatThrownBy(() -> members.list(memberUser)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> members.create(memberUser, fixture(number())))
            .isInstanceOf(SecurityException.class);
    }

    @Test
    @Transactional
    void duplicateIdentityBlindIndexIsRejectedByDatabase() {
        UUID officer = userWithRole("TREASURER");
        members.create(officer, fixture(number()));
        assertThatThrownBy(() -> members.create(officer, fixture(number())))
            .isInstanceOf(DuplicateKeyException.class);
    }

    private MemberService.CreateMember fixture(String number) {
        return new MemberService.CreateMember(number, "Ama Example", "ama@invalid.example",
            "0201234001", "GHANA_CARD", "GHA-123-456", LocalDate.of(1990, 1, 2),
            "Example Street", "Ada Example", "0201234002", "Sibling", LocalDate.of(2025, 1, 1));
    }

    private String number() {
        return "CU-SEC-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private UUID userWithRole(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash) VALUES (?, ?, 'fixture-hash')",
            id, "member-test-" + id + "@invalid.example");
        jdbc.update("""
            INSERT INTO user_role_assignments (user_id, role_id, start_date)
            SELECT ?, id, current_date - 1 FROM roles WHERE code = ?
            """, id, role);
        return id;
    }
}
