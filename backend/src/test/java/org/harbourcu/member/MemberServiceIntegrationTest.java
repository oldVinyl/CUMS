package org.harbourcu.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.harbourcu.security.SessionService;
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
    @Autowired SessionService sessions;

    @Test
    @Transactional
    void createsEncryptedMemberAndAuditsPrivateRead() {
        UUID officer = userWithRole("TREASURER");
        String number = number();
        UUID id = members.create(officer, fixture(number));
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
        assertThat(members.list(officer, number, 0, 25).items())
            .anyMatch(item -> item.id().equals(id));
        assertThat(jdbc.queryForList("""
            SELECT action FROM audit_log WHERE entity_id = ? ORDER BY occurred_at
            """, String.class, id)).containsExactlyInAnyOrder("MEMBER_CREATED", "SENSITIVE_MEMBER_VIEW");
    }

    @Test
    @Transactional
    void memberRoleCannotViewOrCreateUnionMemberRecords() {
        UUID memberUser = userWithRole("MEMBER");
        assertThatThrownBy(() -> members.list(memberUser, "", 0, 25))
            .isInstanceOf(SecurityException.class);
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

    @Test
    @Transactional
    void updateRequiresCurrentVersionAndReencryptsPrivateFields() {
        UUID officer = userWithRole("TREASURER");
        UUID id = members.create(officer, fixture(number()));
        MemberService.UpdateMember edit = new MemberService.UpdateMember(0, "Ama Updated",
            "ama.updated@invalid.example", "0209999000", "GHANA_CARD", "GHA-987-654",
            LocalDate.of(1990, 1, 2), "New Street", "Ada Example", "0201234002", "Sibling");
        assertThat(members.update(officer, id, edit)).isEqualTo(1);
        MemberService.Detail detail = members.get(officer, id, "edit-test");
        assertThat(detail.fullName()).isEqualTo("Ama Updated");
        assertThat(detail.phone()).isEqualTo("0209999000");
        assertThat(detail.version()).isEqualTo(1);
        assertThatThrownBy(() -> members.update(officer, id, edit))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Reload");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'MEMBER_UPDATED'
            """, Integer.class, id)).isEqualTo(1);
    }

    @Test
    @Transactional
    void searchAndPaginationReturnStablePagesAndTreatWildcardsLiterally() {
        UUID officer = userWithRole("TREASURER");
        String prefix = "CU-PAGE-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO members (member_number, full_name, join_date) VALUES (?, ?, ?)",
                prefix + i, "Page Fixture " + i, LocalDate.of(2025, 1, 1));
        }
        MemberService.Page first = members.list(officer, prefix, 0, 2);
        assertThat(first.items()).extracting(MemberService.Summary::memberNumber)
            .containsExactly(prefix + "0", prefix + "1");
        assertThat(first.hasNext()).isTrue();
        assertThat(members.list(officer, prefix, 1, 2).items())
            .extracting(MemberService.Summary::memberNumber).containsExactly(prefix + "2");
        assertThat(members.list(officer, prefix + "%", 0, 25).items()).isEmpty();
        assertThatThrownBy(() -> members.list(officer, "", -1, 25))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional
    void deactivationDisablesLinkedPortalAccountAndRevokesItsSession() {
        UUID officer = userWithRole("TREASURER");
        UUID id = members.create(officer, fixture(number()));
        UUID portal = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, member_id, email, password_hash) VALUES (?, ?, ?, 'fixture-hash')",
            portal, id, "portal-" + portal + "@invalid.example");
        SessionService.Tokens tokens = sessions.create(portal);
        assertThat(members.changeStatus(officer, id, 0, "INACTIVE")).isEqualTo(1);
        assertThat(members.get(officer, id, "status-test").status()).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, portal))
            .isEqualTo("DISABLED");
        assertThat(sessions.resolveAccess(tokens.access())).isEmpty();
        assertThatThrownBy(() -> members.changeStatus(officer, id, 0, "ACTIVE"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(members.changeStatus(officer, id, 1, "ACTIVE")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, portal))
            .isEqualTo("DISABLED");
    }

    @Test
    @Transactional
    void linkedAdministratorMustHaveRoleRemovedBeforeDeactivation() {
        UUID officer = userWithRole("TREASURER");
        UUID id = members.create(officer, fixture(number()));
        UUID admin = userWithRole("ADMIN");
        jdbc.update("UPDATE users SET member_id = ? WHERE id = ?", id, admin);
        assertThatThrownBy(() -> members.changeStatus(officer, id, 0, "INACTIVE"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("administrator");
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
