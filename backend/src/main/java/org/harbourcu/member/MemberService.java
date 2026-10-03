package org.harbourcu.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import org.harbourcu.ledger.LedgerBalanceService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.harbourcu.common.AuditService;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.role.PermissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Member records; private fields are encrypted before PostgreSQL sees them. */
@Service
@ConditionalOnBean(FieldEncryption.class)
public class MemberService {
    public record CreateMember(String memberNumber, String fullName, String email,
                               String phone, String idType, String idNumber,
                               LocalDate dateOfBirth, String address,
                               String nextOfKinName, String nextOfKinPhone,
                               String nextOfKinRelationship, LocalDate joinDate) {}
    public record Summary(UUID id, String memberNumber, String fullName, String status) {}
    public record Page(List<Summary> items, int page, int size, boolean hasNext) {}
    public record BalanceDetail(LocalDate asAt, String principal, String income, String equity) {}
    public record UpdateMember(long version, String fullName, String email,
                               String phone, String idType, String idNumber,
                               LocalDate dateOfBirth, String address,
                               String nextOfKinName, String nextOfKinPhone,
                               String nextOfKinRelationship) {}
    public record Detail(UUID id, String memberNumber, String fullName, String email,
                         String phone, String idType, String idNumber,
                         LocalDate dateOfBirth, String address,
                         String nextOfKinName, String nextOfKinPhone,
                         String nextOfKinRelationship, LocalDate joinDate, String status,
                         long version, String joinDateBasis) {}
    private record Kin(String name, String phone, String relationship) {}

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private final JdbcTemplate jdbc;
    private final FieldEncryption fields;
    private final PermissionService permissions;
    private final AuditService audit;
    private final LedgerBalanceService balances;
    private final ObjectMapper json;

    public MemberService(JdbcTemplate jdbc, FieldEncryption fields,
                         PermissionService permissions, AuditService audit,
                         LedgerBalanceService balances, ObjectMapper json) {
        this.jdbc = jdbc;
        this.fields = fields;
        this.permissions = permissions;
        this.audit = audit;
        this.balances = balances;
        this.json = json;
    }

    @Transactional
    public UUID create(UUID actorId, CreateMember input) {
        require(actorId, "MEMBER_WRITE");
        if (input == null) throw new IllegalArgumentException("Member details are required");
        String number = optional(input.memberNumber(), 40);
        if (number == null) {
            Long next = jdbc.queryForObject("SELECT nextval('member_number_seq')", Long.class);
            number = "CU-" + String.format(Locale.ROOT, "%06d", next);
        }
        String name = required(input.fullName(), 200, "Full name");
        String email = optional(input.email(), 320);
        validateEmail(email);
        String phone = optional(input.phone(), 40);
        String idType = optional(input.idType(), 60);
        String idNumber = optional(input.idNumber(), 120);
        String address = optional(input.address(), 2000);
        if (input.joinDate() == null || input.joinDate().isAfter(LocalDate.now(BUSINESS_ZONE))) {
            throw new IllegalArgumentException("Join date must be today or earlier");
        }
        if (input.dateOfBirth() != null && !input.dateOfBirth().isBefore(input.joinDate())) {
            throw new IllegalArgumentException("Date of birth must precede join date");
        }
        if (idNumber != null && idType == null) {
            throw new IllegalArgumentException("ID type is required with an ID number");
        }
        Kin kin = new Kin(optional(input.nextOfKinName(), 200),
            optional(input.nextOfKinPhone(), 40), optional(input.nextOfKinRelationship(), 80));
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO members
              (id, member_number, full_name, email, id_type, encrypted_id_number,
               id_number_blind_index, encrypted_phone, phone_blind_index,
               encrypted_date_of_birth, encrypted_address, encrypted_next_of_kin, join_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, number, name, email, idType, fields.encrypt(idNumber),
            idNumber == null ? null : fields.blindIndex("member-id", idNumber.toUpperCase(Locale.ROOT)),
            fields.encrypt(phone), phone == null ? null : fields.blindIndex("member-phone", normalizePhone(phone)),
            fields.encrypt(input.dateOfBirth() == null ? null : input.dateOfBirth().toString()),
            fields.encrypt(address), fields.encrypt(kin.name() == null && kin.phone() == null
                && kin.relationship() == null ? null : toJson(kin)), input.joinDate());
        audit.record(actorId, AuditService.Action.MEMBER_CREATED, "MEMBER", id, null);
        return id;
    }

    public Page list(UUID actorId, String search, int page, int size) {
        require(actorId, "MEMBER_READ");
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid page or page size");
        }
        String term = optional(search, 100);
        String pattern = term == null ? "%" : "%" + term.toLowerCase(Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Summary> rows = jdbc.query("""
            SELECT id, member_number, full_name, status FROM members
            WHERE lower(member_number) LIKE ? ESCAPE '\\'
               OR lower(full_name) LIKE ? ESCAPE '\\'
            ORDER BY member_number, id LIMIT ? OFFSET ?
            """, (row, ignored) -> new Summary(row.getObject("id", UUID.class),
            row.getString("member_number"), row.getString("full_name"), row.getString("status")),
            pattern, pattern, size + 1, page * size);
        return new Page(rows.size() > size ? rows.subList(0, size) : rows,
            page, size, rows.size() > size);
    }

    @Transactional
    public BalanceDetail balance(UUID actorId, UUID memberId, LocalDate asAt, String requestId) {
        require(actorId, "MEMBER_READ");
        LedgerBalanceService.Balance balance = balances.asAt(memberId, asAt);
        audit.record(actorId, AuditService.Action.MEMBER_BALANCE_VIEW, "MEMBER", memberId, requestId);
        return new BalanceDetail(asAt, balance.principal().toPlainString(),
            balance.income().toPlainString(), balance.equity().toPlainString());
    }

    @Transactional
    public long changeStatus(UUID actorId, UUID memberId, long version, String targetStatus) {
        require(actorId, "MEMBER_WRITE");
        if (memberId == null || version < 0
            || (!"ACTIVE".equals(targetStatus) && !"INACTIVE".equals(targetStatus))) {
            throw new IllegalArgumentException("Member, version, and status are required");
        }
        List<String> current = jdbc.query("SELECT status FROM members WHERE id = ? FOR UPDATE",
            (row, ignored) -> row.getString("status"), memberId);
        if (current.isEmpty()) throw new IllegalArgumentException("Member not found");
        if (current.getFirst().equals(targetStatus)) {
            throw new IllegalStateException("Member already has this status");
        }
        if ("INACTIVE".equals(targetStatus)) {
            jdbc.query("SELECT id FROM roles WHERE code IN ('ADMIN', 'PRESIDENT') ORDER BY code FOR UPDATE",
                (row, ignored) -> row.getObject("id", UUID.class));
            Boolean privileged = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM users u
                    JOIN user_role_assignments a ON a.user_id = u.id
                    JOIN roles r ON r.id = a.role_id
                    WHERE u.member_id = ? AND u.status = 'ACTIVE'
                      AND a.revoked_at IS NULL AND a.start_date <= current_date
                      AND (a.end_date IS NULL OR a.end_date >= current_date)
                      AND r.code IN ('ADMIN', 'PRESIDENT')
                )
                """, Boolean.class, memberId);
            if (Boolean.TRUE.equals(privileged)) {
                throw new IllegalStateException("Remove the linked administrator or president role first");
            }
        }
        int changed = jdbc.update("""
            UPDATE members SET status = ?, version = version + 1, updated_at = now()
            WHERE id = ? AND version = ?
            """, targetStatus, memberId, version);
        if (changed != 1) {
            throw new IllegalStateException("Member was changed by another user. Reload and try again.");
        }
        if ("INACTIVE".equals(targetStatus)) {
            jdbc.update("UPDATE users SET status = 'DISABLED', updated_at = now() WHERE member_id = ?",
                memberId);
            jdbc.update("""
                UPDATE auth_sessions SET revoked_at = now()
                WHERE user_id IN (SELECT id FROM users WHERE member_id = ?)
                  AND revoked_at IS NULL
                """, memberId);
        }
        audit.record(actorId, "INACTIVE".equals(targetStatus)
            ? AuditService.Action.MEMBER_DEACTIVATED : AuditService.Action.MEMBER_REACTIVATED,
            "MEMBER", memberId, null);
        return version + 1;
    }

    @Transactional
    public long update(UUID actorId, UUID memberId, UpdateMember input) {
        require(actorId, "MEMBER_WRITE");
        if (memberId == null || input == null || input.version() < 0) {
            throw new IllegalArgumentException("Member and version are required");
        }
        String name = required(input.fullName(), 200, "Full name");
        String email = optional(input.email(), 320);
        validateEmail(email);
        String phone = optional(input.phone(), 40);
        String idType = optional(input.idType(), 60);
        String idNumber = optional(input.idNumber(), 120);
        String address = optional(input.address(), 2000);
        if (idNumber != null && idType == null) {
            throw new IllegalArgumentException("ID type is required with an ID number");
        }
        List<LocalDate> dates = jdbc.query("SELECT join_date FROM members WHERE id = ?",
            (row, ignored) -> row.getObject("join_date", LocalDate.class), memberId);
        if (dates.isEmpty()) throw new IllegalArgumentException("Member not found");
        LocalDate joinDate = dates.getFirst();
        if (input.dateOfBirth() != null && !input.dateOfBirth().isBefore(joinDate)) {
            throw new IllegalArgumentException("Date of birth must precede join date");
        }
        Kin kin = new Kin(optional(input.nextOfKinName(), 200),
            optional(input.nextOfKinPhone(), 40), optional(input.nextOfKinRelationship(), 80));
        int changed = jdbc.update("""
            UPDATE members SET full_name = ?, email = ?, id_type = ?,
              encrypted_id_number = ?, id_number_blind_index = ?,
              encrypted_phone = ?, phone_blind_index = ?,
              encrypted_date_of_birth = ?, encrypted_address = ?,
              encrypted_next_of_kin = ?, version = version + 1, updated_at = now()
            WHERE id = ? AND version = ?
            """, name, email, idType, fields.encrypt(idNumber),
            idNumber == null ? null : fields.blindIndex("member-id", idNumber.toUpperCase(Locale.ROOT)),
            fields.encrypt(phone), phone == null ? null : fields.blindIndex("member-phone", normalizePhone(phone)),
            fields.encrypt(input.dateOfBirth() == null ? null : input.dateOfBirth().toString()),
            fields.encrypt(address), fields.encrypt(kin.name() == null && kin.phone() == null
                && kin.relationship() == null ? null : toJson(kin)), memberId, input.version());
        if (changed != 1) {
            throw new IllegalStateException("Member was changed by another user. Reload and try again.");
        }
        audit.record(actorId, AuditService.Action.MEMBER_UPDATED, "MEMBER", memberId, null);
        return input.version() + 1;
    }

    @Transactional
    public Detail get(UUID actorId, UUID memberId, String requestId) {
        require(actorId, "MEMBER_READ");
        List<Detail> rows = jdbc.query("""
            SELECT id, member_number, full_name, email, id_type, encrypted_id_number,
                   encrypted_phone, encrypted_date_of_birth, encrypted_address,
                   encrypted_next_of_kin, join_date, status, version, join_date_basis
            FROM members WHERE id = ?
            """, (row, ignored) -> {
            byte[] kinCipher = row.getBytes("encrypted_next_of_kin");
            Kin kin = kinCipher == null ? new Kin(null, null, null)
                : fromJson(fields.decrypt(kinCipher));
            String dob = fields.decrypt(row.getBytes("encrypted_date_of_birth"));
            return new Detail(row.getObject("id", UUID.class), row.getString("member_number"),
                row.getString("full_name"), row.getString("email"),
                fields.decrypt(row.getBytes("encrypted_phone")), row.getString("id_type"),
                fields.decrypt(row.getBytes("encrypted_id_number")),
                dob == null ? null : LocalDate.parse(dob),
                fields.decrypt(row.getBytes("encrypted_address")),
                kin.name(), kin.phone(), kin.relationship(),
                row.getObject("join_date", LocalDate.class), row.getString("status"),
                row.getLong("version"), row.getString("join_date_basis"));
        }, memberId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Member not found");
        audit.record(actorId, AuditService.Action.SENSITIVE_MEMBER_VIEW, "MEMBER", memberId, requestId);
        return rows.getFirst();
    }

    private void require(UUID actorId, String permission) {
        if (!permissions.hasPermission(actorId, permission)) {
            throw new SecurityException("Permission denied");
        }
    }

    private String required(String value, int limit, String label) {
        String result = optional(value, limit);
        if (result == null) throw new IllegalArgumentException(label + " is required");
        return result;
    }

    private String optional(String value, int limit) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() > limit) throw new IllegalArgumentException("Field is too long");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizePhone(String phone) {
        return phone.replaceAll("[\\s()-]", "");
    }

    private void validateEmail(String email) {
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
    }

    private String toJson(Kin kin) {
        try { return json.writeValueAsString(kin); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private Kin fromJson(String value) {
        try { return json.readValue(value, Kin.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}
