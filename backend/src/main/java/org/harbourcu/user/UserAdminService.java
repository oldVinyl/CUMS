package org.harbourcu.user;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.role.PermissionService;
import org.harbourcu.member.MemberService;
import org.harbourcu.security.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @ConditionalOnBean(FieldEncryption.class) public class UserAdminService {
 public record Role(UUID id,String code,LocalDate startDate,LocalDate endDate){}
 public record User(UUID id,String email,String status,String memberNumber,List<Role> roles){}
 public record Page(List<User> users,int page,boolean hasNext){}
 public record Pending(UUID id,UUID makerId,JsonNode payload,int approvals,int requiredApprovals){}
 public record Invite(String url,Instant expiresAt){}
 private final MemberService members;private final JdbcTemplate jdbc;private final PermissionService permissions;private final FieldEncryption fields;private final PasswordHasher passwords;private final ApprovalService approvals;private final ObjectMapper json;private final String origin;
 public UserAdminService(JdbcTemplate jdbc,PermissionService permissions,FieldEncryption fields,PasswordHasher passwords,ApprovalService approvals,ObjectMapper json,MemberService members,@Value("${APP_ALLOWED_ORIGIN:http://localhost:5173}")String origin){this.members=members;this.jdbc=jdbc;this.permissions=permissions;this.fields=fields;this.passwords=passwords;this.approvals=approvals;this.json=json;this.origin=origin;}
 public Page list(UUID actor,String search,int page){if(!permissions.hasPermission(actor,"USER_ADMIN")&&!permissions.hasPermission(actor,"ROLE_ASSIGN"))throw new SecurityException("Access denied");if(page<0||page>10000||search==null||search.length()>200)throw new IllegalArgumentException("Choose a valid search and page.");
  String q="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
  var rows=jdbc.query("SELECT u.id,u.email,u.status,m.member_number FROM users u LEFT JOIN members m ON m.id=u.member_id WHERE u.email ILIKE ? OR m.member_number ILIKE ? ORDER BY lower(u.email),u.id LIMIT 26 OFFSET ?",(r,n)->new User(r.getObject("id",UUID.class),r.getString("email"),r.getString("status"),r.getString("member_number"),roles(r.getObject("id",UUID.class))),q,q,page*25);
  return new Page(rows.size()>25?rows.subList(0,25):rows,page,rows.size()>25);
 }
 private List<Role> roles(UUID user){return jdbc.query("SELECT a.id,r.code,a.start_date,a.end_date FROM user_role_assignments a JOIN roles r ON r.id=a.role_id WHERE a.user_id=? AND a.revoked_at IS NULL AND a.start_date<=current_date AND (a.end_date IS NULL OR a.end_date>=current_date) ORDER BY r.code",(r,n)->new Role(r.getObject("id",UUID.class),r.getString("code"),r.getDate("start_date").toLocalDate(),r.getDate("end_date")==null?null:r.getDate("end_date").toLocalDate()),user);}
 @Transactional public Invite invite(UUID actor,UUID member,String email){
  require(actor,"USER_ADMIN");if(member==null||email==null||email.length()>320||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Choose a member and valid sign-in email.");
  jdbc.queryForList("SELECT id FROM members WHERE id=? FOR UPDATE",member);
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE id=? AND status='ACTIVE')",Boolean.class,member)))throw new IllegalArgumentException("Member is not active.");
  var existing=jdbc.queryForList("SELECT id,member_id,status,password_hash FROM users WHERE member_id=? OR lower(email)=lower(?) FOR UPDATE",member,email);
  UUID user;
  if(existing.isEmpty()){
   user=UUID.randomUUID();jdbc.update("INSERT INTO users(id,member_id,email,password_hash,status,force_password_change) VALUES(?,?,?,'INVITATION_PENDING','DISABLED',false)",user,member,email.trim().toLowerCase(Locale.ROOT));
   jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date,assigned_by,reason) SELECT ?,id,?,?, 'Membership invitation' FROM roles WHERE code='MEMBER'",user,LocalDate.now(ZoneId.of("Africa/Accra")),actor);
  }else{
   if(existing.size()!=1||!member.equals(existing.getFirst().get("member_id"))||!"DISABLED".equals(existing.getFirst().get("status"))||!"INVITATION_PENDING".equals(existing.getFirst().get("password_hash")))throw new IllegalStateException("This member or email already has an active account.");
   user=(UUID)existing.getFirst().get("id");jdbc.update("UPDATE users SET email=? WHERE id=?",email.trim().toLowerCase(Locale.ROOT),user);
   jdbc.update("UPDATE account_invitations SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",user);
  }
  return issue(actor,user,null);
 }
 @Transactional public Invite inviteEmail(UUID actor,String email,String fullName){
  require(actor,"USER_ADMIN");require(actor,"MEMBER_WRITE");
  if(email==null||email.length()>320||!email.trim().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")||fullName==null||fullName.isBlank()||fullName.trim().length()>200)throw new IllegalArgumentException("Enter the recipient's full name and a valid email.");
  email=email.trim().toLowerCase(Locale.ROOT);fullName=fullName.trim();
  jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,734019))",email);
  var memberships=jdbc.queryForList("SELECT id,status FROM members WHERE lower(email)=?",email);
  if(memberships.size()>1)throw new IllegalStateException("Several memberships use this contact email. Resolve the duplicate contact addresses before inviting.");
  if(memberships.size()==1){if(!"ACTIVE".equals(memberships.getFirst().get("status")))throw new IllegalStateException("This email belongs to an inactive membership. Review that member first.");return invite(actor,(UUID)memberships.getFirst().get("id"),email);}
  var accounts=jdbc.queryForList("SELECT id,member_id,status,password_hash FROM users WHERE lower(email)=? FOR UPDATE",email);UUID user;
  if(accounts.isEmpty()){
   user=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash,status,force_password_change) VALUES(?,?,'INVITATION_PENDING','DISABLED',false)",user,email);
   jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date,assigned_by,reason) SELECT ?,id,?,?,'Email membership invitation' FROM roles WHERE code='MEMBER'",user,LocalDate.now(ZoneId.of("Africa/Accra")),actor);
  }else{
   var account=accounts.getFirst();if(account.get("member_id")!=null||!"DISABLED".equals(account.get("status"))||!"INVITATION_PENDING".equals(account.get("password_hash")))throw new IllegalStateException("This email already has an account. Review it instead of inviting again.");
   user=(UUID)account.get("id");jdbc.update("UPDATE account_invitations SET consumed_at=now() WHERE user_id=? AND consumed_at IS NULL",user);
  }
  return issue(actor,user,fullName);
 }
 private Invite issue(UUID actor,UUID user,String fullName){
  byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token=HexFormat.of().formatHex(random);Instant expiry=Instant.now().plus(Duration.ofHours(72));
  jdbc.update("INSERT INTO account_invitations(token_hash,user_id,invited_by,expires_at,prospective_full_name) VALUES(?,?,?,?,?)",hash(token),user,actor,java.sql.Timestamp.from(expiry),fullName);String url=origin+"/accept-invitation#token="+token;
  var payload=json.createObjectNode().put("encryptedLink",Base64.getEncoder().encodeToString(fields.encrypt(url)));
  jdbc.update("INSERT INTO notifications(user_id,entity_id,kind,outcome,payload) VALUES(?,?,'INVITATION','PENDING',?::jsonb) ON CONFLICT(user_id,entity_id,kind,outcome) DO UPDATE SET payload=excluded.payload,status='PENDING',attempts=0,next_attempt_at=now(),sent_at=NULL",user,user,payload.toString());
  jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'ACCOUNT_INVITED','USER',?)",actor,user);return new Invite(url,expiry);
 }
 @Transactional public void accept(String token,String password){
  if(token==null||!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invitation is invalid or expired.");
  var addresses=jdbc.queryForList("SELECT u.email FROM account_invitations i JOIN users u ON u.id=i.user_id WHERE i.token_hash=?",hash(token));
  if(addresses.isEmpty())throw new IllegalArgumentException("Invitation is invalid or expired.");
  jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,734019))",addresses.getFirst().get("email"));
  var rows=jdbc.queryForList("SELECT i.user_id,i.invited_by,i.prospective_full_name,u.member_id,u.email FROM account_invitations i JOIN users u ON u.id=i.user_id LEFT JOIN members m ON m.id=u.member_id WHERE i.token_hash=? AND i.consumed_at IS NULL AND i.expires_at>now() AND u.status='DISABLED' AND u.password_hash='INVITATION_PENDING' AND (m.status='ACTIVE' OR (u.member_id IS NULL AND i.prospective_full_name IS NOT NULL)) FOR UPDATE OF i,u",hash(token));
  if(rows.isEmpty())throw new IllegalArgumentException("Invitation is invalid or expired.");UUID user=(UUID)rows.getFirst().get("user_id");String encoded=passwords.hashNewPassword(password);
  if(rows.getFirst().get("member_id")==null){
   UUID inviter=(UUID)rows.getFirst().get("invited_by");
   if(!permissions.hasPermission(inviter,"USER_ADMIN")||!permissions.hasPermission(inviter,"MEMBER_WRITE"))throw new IllegalArgumentException("Invitation is no longer authorized. Ask the union for a new invitation.");
   if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE lower(email)=lower(?))",Boolean.class,rows.getFirst().get("email"))))throw new IllegalArgumentException("A membership now uses this email. Ask the union for a new invitation linked to that membership.");
   UUID member=members.create(inviter,new MemberService.CreateMember(null,(String)rows.getFirst().get("prospective_full_name"),(String)rows.getFirst().get("email"),null,null,null,null,null,null,null,null,LocalDate.now(ZoneId.of("Africa/Accra"))));
   jdbc.update("UPDATE users SET member_id=? WHERE id=?",member,user);
  }

  jdbc.update("UPDATE users SET password_hash=?,status='ACTIVE',email_verified_at=now(),password_changed_at=now(),force_password_change=false WHERE id=?",encoded,user);
  jdbc.update("UPDATE account_invitations SET consumed_at=now() WHERE token_hash=?",hash(token));jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'INVITATION_ACCEPTED','USER',?)",user,user);
 }
 @Transactional public UUID role(UUID actor,JsonNode payload){require(actor,"ROLE_ASSIGN");if(payload==null||payload.path("reason").asText().length()>1000)throw new IllegalArgumentException("A role-change reason up to 1,000 characters is required.");return approvals.submit("ROLE_CHANGE",payload,actor,1);}
 public List<Pending> pending(UUID actor){if(!permissions.hasPermission(actor,"ROLE_ASSIGN")&&!permissions.hasPermission(actor,"APPROVE"))throw new SecurityException("Access denied");return jdbc.query("SELECT r.id,r.made_by,r.payload::text,r.required_approvals,(SELECT count(*) FROM approval_decisions d WHERE d.request_id=r.id AND d.decision='APPROVE') AS approvals FROM approval_requests r WHERE action_type='ROLE_CHANGE' AND status='PENDING' ORDER BY created_at LIMIT 100",(r,n)->new Pending(r.getObject("id",UUID.class),r.getObject("made_by",UUID.class),tree(r.getString("payload")),r.getInt("approvals"),r.getInt("required_approvals")));}
 @Transactional public void decide(UUID actor,UUID id,String decision,String reason){require(actor,"APPROVE");request(id);approvals.decide(id,actor,ApprovalService.Decision.valueOf(decision),reason);}
 @Transactional public void cancel(UUID actor,UUID id){require(actor,"ROLE_ASSIGN");request(id);approvals.cancel(id,actor);}
 private void request(UUID id){if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE id=? AND action_type='ROLE_CHANGE')",Boolean.class,id)))throw new IllegalArgumentException("Role request not found.");}
 private JsonNode tree(String s){try{return json.readTree(s);}catch(java.io.IOException e){throw new IllegalStateException("Invalid stored role request.");}}
 private byte[] hash(String token){try{return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 private void require(UUID actor,String code){if(!permissions.hasPermission(actor,code))throw new SecurityException("Access denied");}
}
