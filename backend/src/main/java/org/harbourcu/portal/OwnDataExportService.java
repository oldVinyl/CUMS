package org.harbourcu.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.harbourcu.common.FieldEncryption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Explicit field allowlists prevent authentication secrets or another member's data entering an export. */
@Service @ConditionalOnBean(FieldEncryption.class)
public class OwnDataExportService {
    private final JdbcTemplate jdbc;
    private final PortalService portal;
    private final FieldEncryption fields;
    private final ObjectMapper json;
    public OwnDataExportService(JdbcTemplate jdbc, PortalService portal, FieldEncryption fields, ObjectMapper json) {
        this.jdbc=jdbc;this.portal=portal;this.fields=fields;this.json=json;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public byte[] export(UUID actor) {
        UUID member=portal.member(actor);
        ObjectNode root=json.createObjectNode().put("schemaVersion",1).put("exportedAt",Instant.now().toString());
        ObjectNode identity=object(jdbc.queryForMap("SELECT id,member_number,full_name,email,id_type,join_date,join_date_basis,status,version,created_at,updated_at FROM members WHERE id=?",member));
        Map<String,Object> encrypted=jdbc.queryForMap("SELECT encrypted_phone,encrypted_id_number,encrypted_date_of_birth,encrypted_address,encrypted_next_of_kin FROM members WHERE id=?",member);
        for(var field:Map.of("phone","encrypted_phone","id_number","encrypted_id_number","date_of_birth","encrypted_date_of_birth","address","encrypted_address").entrySet()) {
            identity.put(field.getKey(),fields.decrypt((byte[])encrypted.get(field.getValue())));
        }
        String kin=fields.decrypt((byte[])encrypted.get("encrypted_next_of_kin"));
        try { identity.set("next_of_kin",kin==null?json.nullNode():json.readTree(kin)); }
        catch(java.io.IOException e){throw new IllegalStateException("Your export could not be prepared. Please contact support.");}
        root.set("member",identity);
        root.set("account",object(jdbc.queryForMap("SELECT id,email,status,created_at,updated_at FROM users WHERE id=?",actor)));
        section(root,"ledger","""
            SELECT id,type,component,amount,effective_date,original_effective_date,recorded_at,fiscal_year,
              reference,narration,reversal_of,investment_id,payment_id,batch_id
            FROM ledger_entries WHERE member_id=? ORDER BY effective_date,recorded_at,id
            """,member);
        section(root,"payments","""
            SELECT id,amount,method,provider,provider_reference,internal_reference,status,purpose,fee_amount,
              paid_date,effective_date,verified_amount,submitted_at,confirmed_at,rejection_reason,created_at,proof_id,ledger_entry_id
            FROM payments WHERE member_id=? ORDER BY created_at,id
            """,member);
        section(root,"investment_allocations","""
            SELECT a.investment_id,i.tenor,i.institution,i.purchase_date,i.maturity_date,i.status,i.revision,
              a.participation,a.ratio_basis_points,a.cost_share,a.maturity_value_share,a.income_share,a.closing_balance
            FROM investment_allocations a JOIN investments i ON i.id=a.investment_id
            WHERE a.member_id=? ORDER BY i.purchase_date,i.id
            """,member);
        section(root,"investment_revision_history","""
            SELECT h.investment_id,h.revision,h.recorded_at,
              h.snapshot->'investment'->>'purchase_date' purchase_date,
              h.snapshot->'investment'->>'maturity_date' maturity_date,
              h.snapshot->'investment'->>'status' status,
              a->>'participation' participation,a->>'cost_share' cost_share,
              a->>'maturity_value_share' maturity_value_share,a->>'income_share' income_share,a->>'closing_balance' closing_balance
            FROM investment_revision_history h CROSS JOIN LATERAL
              jsonb_array_elements(coalesce(nullif(h.snapshot->'allocations','null'::jsonb),'[]'::jsonb)) a
            WHERE a->>'member_id'=?::text ORDER BY h.investment_id,h.revision
            """,member);
        section(root,"maturity_allocations","SELECT investment_id,revision,actual_income_share FROM investment_maturity_allocations WHERE member_id=? ORDER BY investment_id,revision",member);
        section(root,"fiscal_snapshots","SELECT fiscal_year,principal,income,equity,revision,created_at FROM fiscal_year_snapshots WHERE member_id=? ORDER BY fiscal_year",member);
        section(root,"snapshot_history","SELECT fiscal_year,principal,income,equity,revision,original_created_at,archived_at FROM fiscal_snapshot_history WHERE member_id=? ORDER BY fiscal_year,revision",member);
        section(root,"receiptRevisions","SELECT r.id,r.payment_id,r.previous_proof_id,r.new_proof_id,r.amended_by,r.amended_at FROM payment_receipt_revisions r JOIN payments p ON p.id=r.payment_id WHERE p.member_id=? ORDER BY r.amended_at,r.id",member);
        section(root,"receipts","SELECT id,content_type,byte_size,created_at FROM attachments WHERE member_id=? ORDER BY created_at,id",member);
        section(root,"role_history","SELECT r.code,a.start_date,a.end_date,a.revoked_at FROM user_role_assignments a JOIN roles r ON r.id=a.role_id WHERE a.user_id=? ORDER BY a.created_at,a.id",actor);
        section(root,"passkeys","SELECT id,created_at,last_used_at,backup_eligible,backed_up,revoked_at FROM passkey_credentials WHERE user_id=? ORDER BY created_at",actor);
        byte[] bytes;
        try { bytes=json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root); }
        catch(java.io.IOException e){throw new IllegalStateException("Your export could not be prepared. Please retry.");}
        jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id,details) VALUES(?,'OWN_DATA_EXPORTED','MEMBER',?,jsonb_build_object('schemaVersion',1,'byteSize',?))",actor,member,bytes.length);
        return bytes;
    }
    private void section(ObjectNode root,String name,String sql,UUID owner) {
        var array=json.createArrayNode();
        jdbc.query(sql,rs->{Map<String,Object> row=new LinkedHashMap<>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getObject(i));array.add(object(row));},owner);
        root.set(name,array);
    }
    private ObjectNode object(Map<String,Object> row) {
        ObjectNode node=json.createObjectNode();
        row.forEach((name,value)->{
            if(value==null)node.putNull(name);
            else if(value instanceof BigDecimal amount)node.put(name,amount.toPlainString());
            else if(value instanceof Boolean flag)node.put(name,flag);
            else if(value instanceof Number number)node.set(name,json.valueToTree(number));
            else node.put(name,value instanceof java.sql.Timestamp timestamp?timestamp.toInstant().toString():value.toString());
        });
        return node;
    }
}
