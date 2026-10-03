package org.harbourcu.user;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.harbourcu.role.PermissionService;
import org.harbourcu.security.SessionService;
import org.harbourcu.security.PasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
@SpringBootTest(properties="APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") @AutoConfigureMockMvc class UserAdministrationIntegrationTest {
 @Autowired UserAdminService service;@Autowired JdbcTemplate jdbc;@Autowired PasswordHasher passwords;@Autowired PermissionService permissions;@Autowired SessionService sessions;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
 final LocalDate today=LocalDate.now(ZoneId.of("Africa/Accra"));
 @Test @Transactional void invitationCreatesDisabledAccountAndSingleUseEncryptedLink(){UUID admin=user("ADMIN"),member=member();String email=UUID.randomUUID()+"@invalid.example";var invite=service.invite(admin,member,email);String token=invite.url().split("token=")[1];UUID actor=jdbc.queryForObject("SELECT id FROM users WHERE member_id=?",UUID.class,member);
  assertThat(permissions.currentPermissions(actor)).isEmpty();assertThat(jdbc.queryForObject("SELECT payload::text FROM notifications WHERE user_id=?",String.class,actor)).doesNotContain(token);assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,actor)).isEqualTo("INVITATION_PENDING");
  String password="Quartz!Portal2026#Welcome";service.accept(token,password);assertThat(permissions.currentPermissions(actor)).containsExactly("OWN_DATA_READ");assertThat(passwords.verify(password,jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,actor))).isTrue();assertThatThrownBy(()->service.accept(token,password)).hasMessageContaining("invalid or expired");
  assertThat(jdbc.queryForObject("SELECT preferred_sign_in_method FROM users WHERE id=?",String.class,actor)).isNull();
 }
 @Test @Transactional void replacingAnInvitationInvalidatesTheOldLinkAndMemberCannotInvite(){UUID admin=user("ADMIN"),member=member();String email=UUID.randomUUID()+"@invalid.example";String old=service.invite(admin,member,email).url().split("token=")[1];String fresh=service.invite(admin,member,email).url().split("token=")[1];assertThat(fresh).isNotEqualTo(old);assertThatThrownBy(()->service.accept(old,"Quartz!Portal2026#Welcome")).hasMessageContaining("invalid or expired");assertThatThrownBy(()->service.invite(user("MEMBER"),member,"other@invalid.example")).isInstanceOf(SecurityException.class);}
 @Test @Transactional void roleChangesNeedAnIndependentCheckerAndPermissionsUpdateImmediately(){UUID maker=user("ADMIN"),target=user("MEMBER"),checker=user("PRESIDENT");jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code='TREASURER'",maker,today);var payload=json.createObjectNode().put("operation","ASSIGN").put("userId",target.toString()).put("roleCode","TREASURER").put("reason","Elected treasurer fixture");UUID request=service.role(maker,payload);assertThat(permissions.hasPermission(target,"PAYMENT_VERIFY")).isFalse();assertThatThrownBy(()->service.decide(maker,request,"APPROVE",null)).hasMessageContaining("own request");service.decide(checker,request,"APPROVE",null);assertThat(permissions.hasPermission(target,"PAYMENT_VERIFY")).isTrue();assertThat(service.list(maker,"",0).users()).extracting(UserAdminService.User::id).contains(target);}
 @Test @Transactional void accountListAndInvitationAcceptanceHaveAuthorizationAndOriginGuards() throws Exception {var token=sessions.create(user("MEMBER"));mvc.perform(get("/api/v1/users").cookie(new Cookie("cu_access",token.access()))).andExpect(status().isForbidden());mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());mvc.perform(post("/api/v1/auth/invitations/accept").header("Origin","https://foreign.invalid").contentType("application/json").content("{\"token\":\"invalid\",\"password\":\"invalid\"}")).andExpect(status().isForbidden());
  UUID admin=user("ADMIN"),target=user("MEMBER");assertThatThrownBy(()->service.role(admin,json.createObjectNode().put("operation","ASSIGN").put("userId",target.toString()).put("roleCode","PRESIDENT").put("reason","Fixture").put("endDate",today.plusDays(10).toString()))).hasMessageContaining("automatic expiry");
 }
 private UUID member(){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,'Invitation fixture',?)",id,"IV-"+id,today);return id;}
 private UUID user(String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'fixture')",id,id+"@invalid.example");jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code=?",id,today,role);return id;}
}
