package org.harbourcu.member;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
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
    public record Detail(UUID id, String memberNumber, String fullName, String email,
                         String phone, String idType, String idNumber,
                         LocalDate dateOfBirth, String address,
                         String nextOfKinName, String nextOfKinPhone,
                         String nextOfKinRelationship, LocalDate joinDate, String status) {}
    private record Kin(String name, String phone, String relationship) {}

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Accra");
    private final JdbcTemplate jdbc;
    private final FieldEncryption fields;
    private final PermissionService permissions;
    private final AuditService audit;
    private final ObjectMapper json;

    public MemberService(JdbcTemplate jdbc, FieldEncryption fields,
                         PermissionService permissions, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.fields = fields;
        this.permissions = permissions;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public UUID create(UUID actorId, CreateMember input) {
        require(actorId, "MEMBER_WRITE");
        if (input == null) throw new IllegalArgumentException("Member details are required");
        String number = required(input.memberNumber(), 40, "Member number");
        String name = required(input.fullName(), 200, "Full name");
        String email = optional(input.email(), 320);
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

    public List<Summary> list(UUID actorId) {
        require(actorId, "MEMBER_READ");
        return jdbc.query("""
            SELECT id, member_number, full_name, status FROM members
            ORDER BY member_number LIMIT 500
            """, (row, ignored) -> new Summary(row.getObject("id", UUID.class),
            row.getString("member_number"), row.getString("full_name"), row.getString("status")));
    }

    @Transactional
    public Detail get(UUID actorId, UUID memberId, String requestId) {
        require(actorId, "MEMBER_READ");
        List<Detail> rows = jdbc.query("""
            SELECT id, member_number, full_name, email, id_type, encrypted_id_number,
                   encrypted_phone, encrypted_date_of_birth, encrypted_address,
                   encrypted_next_of_kin, join_date, status
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
                row.getObject("join_date", LocalDate.class), row.getString("status"));
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

    private String toJson(Kin kin) {
        try { return json.writeValueAsString(kin); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    private Kin fromJson(String value) {
        try { return json.readValue(value, Kin.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}
