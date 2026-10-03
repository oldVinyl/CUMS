package org.harbourcu.security;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component @Profile("dev") @Order(200) @ConditionalOnProperty(name="DEV_SAMPLE_DATA",havingValue="true")
public class DevPortalSampleRunner implements CommandLineRunner {
 private final JdbcTemplate jdbc;private final Environment environment;
 public DevPortalSampleRunner(JdbcTemplate jdbc,Environment environment){this.jdbc=jdbc;this.environment=environment;}
 @Transactional public void run(String...args){
  String email=environment.getProperty("DEV_BOOTSTRAP_EMAIL");if(email==null||email.isBlank())return;
  UUID demo=UUID.nameUUIDFromBytes("cums-dev-member-0".getBytes(StandardCharsets.UTF_8));
  var users=jdbc.queryForList("UPDATE users SET member_id=? WHERE lower(email)=lower(?) AND member_id IS NULL AND NOT EXISTS(SELECT 1 FROM users WHERE member_id=?) RETURNING id",UUID.class,demo,email,demo);
  for(UUID actor:users)jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'DEV_ACCOUNT_LINKED_DEMO_MEMBER','MEMBER',?)",actor,demo);
  // A disabled fictitious reviewer makes local two-account approval testing possible.
  UUID reviewer=UUID.nameUUIDFromBytes("cums-dev-reviewer".getBytes(StandardCharsets.UTF_8));
  UUID reviewerMember=UUID.nameUUIDFromBytes("cums-dev-member-1".getBytes(StandardCharsets.UTF_8));
  var created=jdbc.queryForList("INSERT INTO users(id,member_id,email,password_hash,status,force_password_change) VALUES(?,?,'dev-reviewer@invalid.example','INVITATION_PENDING','DISABLED',false) ON CONFLICT DO NOTHING RETURNING id",UUID.class,reviewer,reviewerMember);
  for(UUID id:created){jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date,reason) SELECT ?,id,current_date,'Fictitious development reviewer' FROM roles WHERE code IN ('MEMBER','TREASURER')",id);jdbc.update("INSERT INTO audit_log(action,entity_type,entity_id) VALUES('DEV_REVIEWER_CREATED','USER',?)",id);}
  UUID method=UUID.nameUUIDFromBytes("cums-dev-payment-method".getBytes(StandardCharsets.UTF_8));
  jdbc.update("INSERT INTO payment_methods(id,name,instructions) VALUES(?,'Development receipt test','Fictitious method for local workflow testing. Do not send real funds. Upload a sample receipt and use the generated reference.') ON CONFLICT(id) DO NOTHING",method);
 }
}
