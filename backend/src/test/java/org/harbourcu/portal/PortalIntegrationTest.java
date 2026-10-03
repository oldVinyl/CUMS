package org.harbourcu.portal;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.UUID;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties="APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") @AutoConfigureMockMvc class PortalIntegrationTest {
 @Autowired PortalService portal;@Autowired JdbcTemplate jdbc;@Autowired SessionService sessions;@Autowired MockMvc mvc;
 final LocalDate today=LocalDate.now(ZoneId.of("Africa/Accra"));
 @Test @Transactional void ownDashboardAndHistoryNeverIncludeAnotherMembersIdentityOrEntries() throws Exception {
  UUID mine=member("My fixture"),other=member("PRIVATE OTHER NAME"),actor=user(mine);
  entry(mine,"PRINCIPAL","25.00");entry(mine,"INCOME","5.00");entry(other,"PRINCIPAL","70.00");
  var d=portal.overview(actor,today);assertThat(d.memberId()).isEqualTo(mine);assertThat(d.total().equity()).isEqualTo("30.0000");assertThat(d.roePercent()).isEqualTo("20.00");assertThat(d.equityRatioPercent()).isEqualTo("30.00");
  assertThat(portal.history(actor,today,0).entries()).hasSize(2);
  var token=sessions.create(actor);String body=mvc.perform(get("/api/v1/portal").param("asAt",today.toString()).param("memberId",other.toString()).cookie(new Cookie("cu_access",token.access()))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
  assertThat(body).doesNotContain("PRIVATE OTHER NAME",other.toString());
  mvc.perform(get("/api/v1/portal/ledger").param("asAt",today.toString()).param("memberId",other.toString()).cookie(new Cookie("cu_access",token.access()))).andExpect(status().isOk()).andExpect(jsonPath("$.entries.length()").value(2));
 }
 @Test @Transactional void contactUpdatesAreEncryptedVersionedAndCannotChangeOtherPrivateFields(){UUID member=member("My fixture"),actor=user(member);var profile=portal.profile(actor);
  var update=new PortalService.Profile(profile.version(),"contact@example.invalid","+233 20 123 4567","Kin fixture","+233201234568","Sibling");portal.update(actor,update);
  assertThatThrownBy(()->portal.update(actor,update)).hasMessageContaining("Reload");
  var stored=portal.profile(actor);assertThat(stored.phone()).isEqualTo("+233 20 123 4567");assertThat(stored.nextOfKinName()).isEqualTo("Kin fixture");assertThat(stored.version()).isEqualTo(profile.version()+1);
  assertThat(new String(jdbc.queryForObject("SELECT encrypted_phone FROM members WHERE id=?",byte[].class,member))).doesNotContain("233 20");
  assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id=?",String.class,actor)).isEqualTo(actor+"@invalid.example");
 }
 @Test @Transactional void emptyUnlinkedAndDisabledMembershipsHaveSafeResponses() throws Exception {
  UUID member=member("Empty fixture"),actor=user(member);assertThat(portal.overview(actor,today).total().equity()).isEqualTo("0");assertThat(portal.history(actor,today,0).entries()).isEmpty();
  var token=sessions.create(user(null));mvc.perform(get("/api/v1/portal").param("asAt",today.toString()).cookie(new Cookie("cu_access",token.access()))).andExpect(status().isConflict());
  jdbc.update("UPDATE members SET status='INACTIVE' WHERE id=?",member);assertThatThrownBy(()->portal.history(actor,today,0)).hasMessageContaining("active membership");
  mvc.perform(get("/api/v1/portal").param("asAt",today.toString())).andExpect(status().isUnauthorized());
 }
 private void entry(UUID m,String c,String amount){jdbc.update("INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference) VALUES(?,'OPENING_BALANCE',?,?,?,?,?)",m,c,new java.math.BigDecimal(amount),today,today.getYear(),UUID.randomUUID().toString());}
 private UUID member(String name){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO members(id,member_number,full_name,join_date) VALUES(?,?,?,?)",id,"PT-"+id,name,today);return id;}
 private UUID user(UUID member){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash,member_id) VALUES(?,?,'fixture',?)",id,id+"@invalid.example",member);jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,? FROM roles WHERE code='MEMBER'",id,today);return id;}
}
