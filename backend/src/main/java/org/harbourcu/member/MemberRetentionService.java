package org.harbourcu.member;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.role.PermissionService;
import org.harbourcu.storage.ReceiptObjectStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @ConditionalOnProperty(name="APP_ENCRYPTION_KEY")
public class MemberRetentionService {
  public record Candidate(UUID memberId,String memberNumber,LocalDate leftOn,LocalDate eligibleOn,long version) {}
  public record Pending(UUID id,UUID memberId,String memberNumber,UUID makerId,int approvals,int requiredApprovals,String reason) {}
  public record Overview(int retentionYears,List<Candidate> candidates,List<Candidate> unconfirmedDepartures,List<Pending> pending,int pendingReceiptDeletions) {}
  public record Input(UUID memberId,String sourceFingerprint,String reason) {}
  public record Preview(UUID memberId,String memberNumber,LocalDate leftOn,LocalDate eligibleOn,String sourceFingerprint,int privateImportRows,int receipts,String consequence) {}
  private record Value(Object value,String sqlType) {}
  private final JdbcTemplate jdbc;private final PermissionService permissions;private final FieldEncryption fields;
  private final ObjectMapper json;private final ReceiptObjectStore receipts;
  public MemberRetentionService(JdbcTemplate jdbc,PermissionService permissions,FieldEncryption fields,ObjectMapper json,ReceiptObjectStore receipts) {
    this.jdbc=jdbc;this.permissions=permissions;this.fields=fields;this.json=json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);this.receipts=receipts;
  }
  public Overview overview(UUID actor) {
    reader(actor);int years=years();
    var candidates=jdbc.query("SELECT id,member_number,left_on,version FROM members WHERE status='INACTIVE' AND anonymized_at IS NULL AND left_on IS NOT NULL AND left_on+(? * interval '1 year')<=current_date ORDER BY left_on,id LIMIT 100",(r,n)->new Candidate(r.getObject("id",UUID.class),r.getString("member_number"),r.getDate("left_on").toLocalDate(),r.getDate("left_on").toLocalDate().plusYears(years),r.getLong("version")),years);
    var pending=jdbc.query("SELECT r.id,r.made_by,r.required_approvals,r.payload->>'reason' reason,m.id member_id,m.member_number,(SELECT count(*) FROM approval_decisions d WHERE d.request_id=r.id AND d.decision='APPROVE') approvals FROM approval_requests r JOIN members m ON m.id::text=r.payload->>'memberId' WHERE r.action_type='MEMBER_ANONYMIZE' AND r.status='PENDING' ORDER BY r.created_at LIMIT 100",(r,n)->new Pending(r.getObject("id",UUID.class),r.getObject("member_id",UUID.class),r.getString("member_number"),r.getObject("made_by",UUID.class),r.getInt("approvals"),r.getInt("required_approvals"),r.getString("reason")));
    var unconfirmed=jdbc.query("SELECT id,member_number,version FROM members WHERE status='INACTIVE' AND left_on IS NULL AND anonymized_at IS NULL ORDER BY member_number,id LIMIT 100",(r,n)->new Candidate(r.getObject("id",UUID.class),r.getString("member_number"),null,null,r.getLong("version")));
    return new Overview(years,candidates,unconfirmed,pending,jdbc.queryForObject("SELECT count(*) FROM retention_receipt_deletions WHERE completed_at IS NULL",Integer.class));
  }
  @Transactional public void policy(UUID actor,int years) {maker(actor);if(years<1||years>30)throw new IllegalArgumentException("Retention must be between 1 and 30 years.");jdbc.update("UPDATE organisation_settings SET member_retention_years=? WHERE singleton",years);audit(actor,null,"MEMBER_RETENTION_POLICY_CHANGED");}
  @Transactional public void departure(UUID actor,UUID member,long version,LocalDate date) {
    maker(actor);if(date==null||date.isAfter(today())||date.isBefore(LocalDate.of(2000,1,1)))throw new IllegalArgumentException("Confirm a leaving date between 2000 and today.");
    if(jdbc.update("UPDATE members SET left_on=?,version=version+1,updated_at=now() WHERE id=? AND version=? AND status='INACTIVE' AND anonymized_at IS NULL AND join_date<=?",date,member,version,date)!=1)throw new IllegalStateException("Choose an inactive membership and reload its current version. Leaving cannot precede joining.");
    audit(actor,member,"MEMBER_DEPARTURE_CONFIRMED");
  }
  @Transactional(readOnly=true) public Preview preview(UUID actor,UUID member) {reader(actor);var row=eligible(member);String number=(String)row.get("member_number");LocalDate left=((java.sql.Date)row.get("left_on")).toLocalDate();
    return new Preview(member,number,left,left.plusYears(years()),fingerprint(row),privateRows(member,number).size(),jdbc.queryForObject("SELECT count(*) FROM attachments WHERE member_id=?",Integer.class,member),"Permanently removes identity/contact fields, linked sign-in factors, associated private import copies and receipt files. Ledger amounts, dates, UUID links and audit metadata remain. Anonymized memberships cannot be reactivated. Earlier backups and original workbooks require the owner's separate retention process.");
  }
  @Transactional public UUID submit(UUID actor,Input input,ApprovalService approvals) {maker(actor);jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");validate(actor,input);if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE action_type='MEMBER_ANONYMIZE' AND status='PENDING' AND payload->>'memberId'=?)",Boolean.class,input.memberId().toString())))throw new IllegalStateException("This member already has a pending anonymization request.");return approvals.submit("MEMBER_ANONYMIZE",json.valueToTree(input),actor,1);}
  @Transactional public void decide(UUID actor,UUID request,String decision,String reason,ApprovalService approvals){linked(request);approvals.decide(request,actor,ApprovalService.Decision.valueOf(decision),reason);}
  @Transactional public void cancel(UUID actor,UUID request,ApprovalService approvals){linked(request);approvals.cancel(request,actor);}
  private void linked(UUID request){jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE id=? AND action_type='MEMBER_ANONYMIZE')",Boolean.class,request)))throw new IllegalArgumentException("Retention request not found.");}
  public void validate(UUID actor,Input input) {maker(actor);if(input==null||input.memberId()==null||input.reason()==null||input.reason().isBlank()||input.reason().length()>1000)throw new IllegalArgumentException("Choose a membership and explain retention approval.");if(!fingerprint(eligible(input.memberId())).equals(input.sourceFingerprint()))throw new IllegalStateException("Membership or retention policy changed. Review a fresh preview.");}
  public void execute(UUID request,UUID checker,Input input) {
    jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");UUID maker=jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id=?",UUID.class,request);validate(maker,input);
    var row=eligible(input.memberId());UUID member=input.memberId();String number=(String)row.get("member_number");
    var imports=privateRows(member,number);var users=jdbc.queryForList("SELECT id FROM users WHERE member_id=?",UUID.class,member);
    jdbc.update("INSERT INTO member_anonymizations(member_id,approval_request_id,made_by,approved_by,left_on,retention_years) VALUES(?,?,?,?,?,?)",member,request,maker,checker,row.get("left_on"),years());
    jdbc.queryForObject("SELECT set_config('cums.retention_request',?,true)",String.class,request.toString());
    String userWhere="(actor_id IN (SELECT id FROM users WHERE member_id=?) OR (entity_type='USER' AND entity_id IN (SELECT id FROM users WHERE member_id=?)))";
    redact(request,"audit_log","(entity_type='MEMBER' AND entity_id=?) OR "+userWhere,new Object[]{member,member,member},Map.of("details",new Value("{}","jsonb"),"request_ip",new Value(null,"inet")));
    redact(request,"ledger_entries","member_id=?",new Object[]{member},Map.of("narration",new Value("Private narrative removed under approved retention","text")));
    redact(request,"payment_events","payment_id IN (SELECT id FROM payments WHERE member_id=?)",new Object[]{member},Map.of("details",new Value("{}","jsonb")));
    redact(request,"investment_allocations","member_id=? AND edit_reason IS NOT NULL",new Object[]{member},Map.of("edit_reason",new Value("Private participation note removed under approved retention","text")));
    for(var stored:imports) {
      Map<String,String> data=decode(fields.decrypt((byte[])stored.get("encrypted_data")));
      for(String key:List.of("full_name","email","phone","id_type","id_number","date_of_birth","address","next_of_kin_name","next_of_kin_phone","next_of_kin_relationship","narration","reference")) if(data.containsKey(key))data.put(key,key.equals("full_name")?"Former member":"");
      redact(request,"import_rows","batch_id=? AND row_number=?",new Object[]{stored.get("batch_id"),stored.get("row_number")},Map.of("encrypted_data",new Value(fields.encrypt(encode(data)),"bytea")));
    }
    // Stored cycle snapshots contain financial UUIDs; remove only their free-text evidence.
    var histories=jdbc.queryForList("SELECT investment_id,revision,snapshot::text FROM investment_revision_history h WHERE EXISTS(SELECT 1 FROM jsonb_array_elements(coalesce(h.snapshot->'allocations','[]'::jsonb)) a WHERE a->>'member_id'=?)",member.toString());
    for(var history:histories) {
      ObjectNode snapshot=(ObjectNode)tree((String)history.get("snapshot"));
      if(snapshot.path("investment") instanceof ObjectNode investment) {investment.put("institution","Historical institution retained in current cycle");investment.put("reference_number","Historical reference removed under approved retention");}
      for(JsonNode allocation:snapshot.path("allocations")) if(allocation instanceof ObjectNode allocationObject && allocationObject.hasNonNull("edit_reason"))allocationObject.put("edit_reason","Private historical note removed under approved retention");
      redact(request,"investment_revision_history","investment_id=? AND revision=?",new Object[]{history.get("investment_id"),history.get("revision")},Map.of("snapshot",new Value(encode(snapshot),"jsonb"),"reason",new Value("Private historical reason removed under approved retention","text")));
    }
    for(UUID user:users) {
      jdbc.update("DELETE FROM consumed_refresh_tokens WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?)",user);
      for(String table:List.of("auth_sessions","auth_challenges","totp_recovery_codes","passkey_credentials","passkey_ceremonies","google_identity_links","account_invitations","account_reset_tokens","notifications"))jdbc.update("DELETE FROM "+table+" WHERE user_id=?",user);
      jdbc.update("UPDATE users SET email=?,password_hash='RETAINED_ACCOUNT_DISABLED',status='DISABLED',totp_secret_ciphertext=NULL,preferred_sign_in_method=NULL,email_verified_at=NULL,updated_at=now() WHERE id=?","former-"+user+"@retained.invalid",user);
    }
    scrubHistoricalText(request,member,row,users);
    jdbc.update("UPDATE payments SET rejection_reason=NULL WHERE member_id=?",member);
    jdbc.update("INSERT INTO retention_receipt_deletions(attachment_id,member_id) SELECT id,member_id FROM attachments WHERE member_id=?",member);
    jdbc.update("UPDATE members SET full_name='Former member',email=NULL,id_type=NULL,encrypted_id_number=NULL,id_number_blind_index=NULL,encrypted_phone=NULL,phone_blind_index=NULL,encrypted_date_of_birth=NULL,encrypted_address=NULL,encrypted_next_of_kin=NULL,anonymized_at=now(),version=version+1,updated_at=now() WHERE id=?",member);
    audit(checker,member,"MEMBER_ANONYMIZED");
  }
  /** Scrub identifiable free text without touching numeric financial facts or JSON identifiers. */
  private void scrubHistoricalText(UUID request,UUID member,Map<String,Object> row,List<UUID> users) {
    Set<String> identifiers=new LinkedHashSet<>();
    for(String key:List.of("full_name","email"))if(row.get(key) instanceof String text && text.length()>=3)identifiers.add(text);
    if(row.get("full_name") instanceof String name)for(String part:name.split("\\s+"))if(part.length()>=3)identifiers.add(part);
    for(String key:List.of("encrypted_phone","encrypted_id_number","encrypted_address","encrypted_next_of_kin"))if(row.get(key) instanceof byte[] encrypted){String value=fields.decrypt(encrypted);if(value!=null&&value.length()>=3){if(key.equals("encrypted_next_of_kin"))collectIdentity(tree(value),identifiers);else identifiers.add(value);}}
    for(var entry:jdbc.queryForList("SELECT id,member_id,reference,narration FROM ledger_entries")) {
      Map<String,Value> changes=new HashMap<>();
      for(String column:List.of("reference","narration"))if(entry.get(column) instanceof String text){String clean=scrub(text,identifiers);if(!clean.equals(text))changes.put(column,new Value(column.equals("reference")?"RET-"+entry.get("id"):clean,"text"));}
      if(!changes.isEmpty())redact(request,"ledger_entries","id=?",new Object[]{entry.get("id")},changes);
    }
    for(var entry:jdbc.queryForList("SELECT id,details::text FROM audit_log")) {String text=(String)entry.get("details");String clean=scrubJson(text,identifiers);if(!tree(clean).equals(tree(text)))redact(request,"audit_log","id=?",new Object[]{entry.get("id")},Map.of("details",new Value(clean,"jsonb"),"request_ip",new Value(null,"inet")));}
    for(var entry:jdbc.queryForList("SELECT id,payload::text FROM approval_requests WHERE id<>?",request)) {String text=(String)entry.get("payload");String clean=scrubJson(text,identifiers);if(!tree(clean).equals(tree(text)))jdbc.update("UPDATE approval_requests SET payload=?::jsonb WHERE id=?",clean,entry.get("id"));}
    for(var entry:jdbc.queryForList("SELECT request_id,approver_id,comment FROM approval_decisions WHERE comment IS NOT NULL")) {String text=(String)entry.get("comment");String clean=scrub(text,identifiers);if(!clean.equals(text))jdbc.update("UPDATE approval_decisions SET comment=? WHERE request_id=? AND approver_id=?",clean,entry.get("request_id"),entry.get("approver_id"));}
    for(var entry:jdbc.queryForList("SELECT id,details::text FROM payment_events")) {String text=(String)entry.get("details");String clean=scrubJson(text,identifiers);if(!tree(clean).equals(tree(text)))redact(request,"payment_events","id=?",new Object[]{entry.get("id")},Map.of("details",new Value(clean,"jsonb")));}
    for(var entry:jdbc.queryForList("SELECT id,encrypted_notes,payment_id FROM payment_settlement_matches")) {String text=fields.decrypt((byte[])entry.get("encrypted_notes"));String clean=scrub(text,identifiers);if(!clean.equals(text))redact(request,"payment_settlement_matches","id=?",new Object[]{entry.get("id")},Map.of("encrypted_notes",new Value(fields.encrypt(clean),"bytea")));}
    for(var entry:jdbc.queryForList("SELECT id,notes FROM reconciliations")) {String text=(String)entry.get("notes");String clean=scrub(text,identifiers);if(!clean.equals(text))redact(request,"reconciliations","id=?",new Object[]{entry.get("id")},Map.of("notes",new Value(clean,"text")));}
    for(var entry:jdbc.queryForList("SELECT id,reason FROM import_batches")) {String text=(String)entry.get("reason");String clean=scrub(text,identifiers);if(!clean.equals(text))jdbc.update("UPDATE import_batches SET reason=? WHERE id=?",clean,entry.get("id"));}
    for(var history:jdbc.queryForList("SELECT investment_id,revision,reason,snapshot::text FROM investment_revision_history")) {
      String original=(String)history.get("snapshot"),clean=scrubJson(original,identifiers);String oldReason=(String)history.get("reason"),newReason=scrub(oldReason,identifiers);
      if(!tree(original).equals(tree(clean))||!oldReason.equals(newReason))redact(request,"investment_revision_history","investment_id=? AND revision=?",new Object[]{history.get("investment_id"),history.get("revision")},Map.of("snapshot",new Value(clean,"jsonb"),"reason",new Value(newReason,"text")));
    }
    for(var entry:jdbc.queryForList("SELECT id,institution,reference_number FROM investments")) {if(entry.get("institution") instanceof String text){String clean=scrub(text,identifiers);if(!clean.equals(text))jdbc.update("UPDATE investments SET institution=? WHERE id=?",clean,entry.get("id"));}if(entry.get("reference_number") instanceof String text && !scrub(text,identifiers).equals(text))jdbc.update("UPDATE investments SET reference_number=? WHERE id=?","RET-"+entry.get("id"),entry.get("id"));}
    // Free-text evidence can mention a departed person in another member's imported row.
    for(var entry:jdbc.queryForList("SELECT batch_id,row_number,encrypted_data FROM import_rows")) {Map<String,String> data=decode(fields.decrypt((byte[])entry.get("encrypted_data")));boolean changed=false;for(String key:List.of("full_name","email","phone","id_number","address","next_of_kin_name","next_of_kin_phone","narration","reference","institution"))if(data.get(key)!=null){String text=data.get(key),clean=scrub(text,identifiers);if(!clean.equals(text)){data.put(key,clean);changed=true;}}if(changed)redact(request,"import_rows","batch_id=? AND row_number=?",new Object[]{entry.get("batch_id"),entry.get("row_number")},Map.of("encrypted_data",new Value(fields.encrypt(encode(data)),"bytea")));}
  }
  private void collectIdentity(JsonNode node,Set<String> identifiers){if(node.isTextual()&&node.asText().length()>=3)identifiers.add(node.asText());else for(JsonNode child:node)collectIdentity(child,identifiers);}
  private String scrub(String text,Set<String> identifiers){String result=text;for(String identifier:identifiers)result=result.replaceAll("(?iu)"+java.util.regex.Pattern.quote(identifier),"Former member");return result;}
  private String scrubJson(String value,Set<String> identifiers){JsonNode node=tree(value);scrubNode(node,identifiers);return encode(node);}
  private void scrubNode(JsonNode node,Set<String> identifiers){if(node instanceof ObjectNode object){var iterator=object.fields();while(iterator.hasNext()){var field=iterator.next();String key=field.getKey();JsonNode value=field.getValue();if(value.isTextual()&&Set.of("reason","comment","narration","notes","full_name","fullName","name","email","phone","address","reference","institution","reference_number","edit_reason").contains(key))object.put(key,scrub(value.asText(),identifiers));else if(value.isContainerNode())scrubNode(value,identifiers);}}else if(node.isArray())for(JsonNode child:node)scrubNode(child,identifiers);}
  @Scheduled(fixedDelayString="${APP_RETENTION_DELETE_INTERVAL_MS:60000}") public void deleteReceipts() {
    var rows=jdbc.queryForList("SELECT d.attachment_id,a.storage_key FROM retention_receipt_deletions d JOIN attachments a ON a.id=d.attachment_id WHERE d.completed_at IS NULL ORDER BY d.attempts,d.attachment_id LIMIT 25");
    for(var row:rows)try {receipts.delete((UUID)row.get("storage_key"));jdbc.update("UPDATE retention_receipt_deletions SET completed_at=now(),attempts=attempts+1 WHERE attachment_id=?",row.get("attachment_id"));}catch(Exception ignored){jdbc.update("UPDATE retention_receipt_deletions SET attempts=attempts+1 WHERE attachment_id=?",row.get("attachment_id"));}
  }
  private Map<String,Object> eligible(UUID member) {
    var rows=jdbc.queryForList("SELECT * FROM members WHERE id=?",member);if(rows.isEmpty())throw new IllegalArgumentException("Membership not found.");var row=rows.getFirst();
    if(!"INACTIVE".equals(row.get("status"))||row.get("anonymized_at")!=null||row.get("left_on")==null||((java.sql.Date)row.get("left_on")).toLocalDate().plusYears(years()).isAfter(today()))throw new IllegalStateException("Membership must be inactive with a confirmed leaving date beyond the retention period.");
    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE member_id=? AND status='ACTIVE') OR EXISTS(SELECT 1 FROM investment_allocations a JOIN investments i ON i.id=a.investment_id WHERE a.member_id=? AND i.status IN ('DRAFT','POSTED')) OR EXISTS(SELECT 1 FROM payments WHERE member_id=? AND status IN ('INITIATED','PENDING','SUBMITTED','UNDER_REVIEW','VERIFIED_PENDING_APPROVAL')) OR EXISTS(SELECT 1 FROM approval_requests WHERE status='PENDING' AND action_type<>'MEMBER_ANONYMIZE')",Boolean.class,member,member,member)))throw new IllegalStateException("Resolve active accounts, open investments, unfinished payments and pending financial/admin requests before anonymization.");
    return row;
  }
  private List<Map<String,Object>> privateRows(UUID member,String number) {
    List<Map<String,Object>> result=new ArrayList<>();
    for(var row:jdbc.queryForList("SELECT r.*,m.member_id FROM import_rows r LEFT JOIN import_member_results m ON m.batch_id=r.batch_id AND m.row_number=r.row_number")) {
      if(member.equals(row.get("member_id"))||number.equals(decode(fields.decrypt((byte[])row.get("encrypted_data"))).get("member_number")))result.add(row);
    } return result;
  }
  private void redact(UUID request,String table,String where,Object[] criteria,Map<String,Value> changes) {
    var keys=new ArrayList<>(changes.keySet());Collections.sort(keys);
    List<Object> authorization=new ArrayList<>(List.of(request,table));List<Object> update=new ArrayList<>();
    List<String> object=new ArrayList<>(),assignments=new ArrayList<>();
    for(String key:keys){Value value=changes.get(key);object.add("'"+key+"',?::"+value.sqlType());assignments.add(key+"=?::"+value.sqlType());authorization.add(value.value());update.add(value.value());}
    authorization.addAll(Arrays.asList(criteria));update.addAll(Arrays.asList(criteria));
    jdbc.update("INSERT INTO retention_redactions(request_id,table_name,old_hash,new_hash) SELECT ?,?,sha256(convert_to(to_jsonb(t)::text,'UTF8')),sha256(convert_to((to_jsonb(t)||jsonb_build_object("+String.join(",",object)+"))::text,'UTF8')) FROM "+table+" t WHERE "+where+" ON CONFLICT DO NOTHING",authorization.toArray());
    jdbc.update("UPDATE "+table+" SET "+String.join(",",assignments)+" WHERE "+where,update.toArray());
  }
  private int years(){return jdbc.queryForObject("SELECT member_retention_years FROM organisation_settings WHERE singleton",Integer.class);}
  private String fingerprint(Map<String,Object> row){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((row.get("id")+":"+row.get("version")+":"+row.get("left_on")+":"+years()).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Retention preview unavailable.");}}
  private void maker(UUID actor){if(!permissions.hasPermission(actor,"SETTINGS_WRITE")||!permissions.hasPermission(actor,"USER_ADMIN")||!permissions.hasPermission(actor,"MEMBER_WRITE"))throw new SecurityException("Organization and membership administration permission required.");}
  private void reader(UUID actor){if(!permissions.hasPermission(actor,"SETTINGS_WRITE")&&!permissions.hasPermission(actor,"APPROVE"))throw new SecurityException("Retention administration permission required.");}
  private void audit(UUID actor,UUID member,String action){jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,?,'MEMBER',?)",actor,action,member);}
  private Map<String,String> decode(String value){try{return json.readValue(value,new TypeReference<Map<String,String>>(){});}catch(Exception e){throw new IllegalStateException("Private import evidence cannot be processed.");}}
  private JsonNode tree(String value){try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("Historical evidence cannot be processed.");}}
  private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Retention evidence cannot be stored.");}}
  private static LocalDate today(){return LocalDate.now(ZoneId.of("Africa/Accra"));}
}
