package org.harbourcu.security;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.ByteArray;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PasskeyCredentialRepository implements CredentialRepository {
    private final JdbcTemplate jdbc;

    public PasskeyCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static ByteArray handle(UUID id) {
        return new ByteArray(ByteBuffer.allocate(16)
            .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array());
    }

    static UUID userId(ByteArray handle) {
        if (handle == null || handle.getBytes().length != 16) return null;
        ByteBuffer bytes = ByteBuffer.wrap(handle.getBytes());
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        List<PublicKeyCredentialDescriptor> credentials = jdbc.query("""
            SELECT p.credential_id FROM passkey_credentials p
            JOIN users u ON u.id = p.user_id WHERE u.email = ?
            """, (row, ignored) -> PublicKeyCredentialDescriptor.builder()
                .id(new ByteArray(row.getBytes("credential_id"))).build(), username);
        return new HashSet<>(credentials);
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return jdbc.query("SELECT id FROM users WHERE email = ?",
            (row, ignored) -> handle(row.getObject(1, UUID.class)), username).stream().findFirst();
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        UUID id = userId(userHandle);
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT email FROM users WHERE id = ? AND status = 'ACTIVE'",
            (row, ignored) -> row.getString(1), id).stream().findFirst();
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        UUID id = userId(userHandle);
        if (id == null) return Optional.empty();
        return jdbc.query("""
            SELECT credential_id, public_key_cose, signature_count, user_id
            FROM passkey_credentials WHERE credential_id = ? AND user_id = ?
            """, (row, ignored) -> credential(row.getBytes("credential_id"),
                row.getBytes("public_key_cose"), row.getLong("signature_count"),
                row.getObject("user_id", UUID.class)), credentialId.getBytes(), id)
            .stream().findFirst();
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return new HashSet<>(jdbc.query("""
            SELECT credential_id, public_key_cose, signature_count, user_id
            FROM passkey_credentials WHERE credential_id = ?
            """, (row, ignored) -> credential(row.getBytes("credential_id"),
                row.getBytes("public_key_cose"), row.getLong("signature_count"),
                row.getObject("user_id", UUID.class)), credentialId.getBytes()));
    }

    private RegisteredCredential credential(byte[] id, byte[] key, long count, UUID user) {
        return RegisteredCredential.builder()
            .credentialId(new ByteArray(id)).userHandle(handle(user))
            .publicKeyCose(new ByteArray(key)).signatureCount(count).build();
    }
}
