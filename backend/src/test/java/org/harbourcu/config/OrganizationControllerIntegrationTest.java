package org.harbourcu.config;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import org.harbourcu.security.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={"spring.flyway.default-schema=organization_tests","spring.datasource.hikari.schema=organization_tests",
 "APP_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","AUTH_THROTTLE_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc @Transactional
class OrganizationControllerIntegrationTest {
 @Autowired MockMvc mvc;@Autowired SessionService sessions;@Autowired JdbcTemplate jdbc;
 SessionService.Tokens account(String role){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,password_hash) VALUES(?,?,'synthetic')",id,id+"@invalid.example");jdbc.update("INSERT INTO user_role_assignments(user_id,role_id,start_date) SELECT ?,id,current_date FROM roles WHERE code=?",id,role);return sessions.create(id);}
 String body(int minimum,String colour,String logo){return "{\"passwordMinLength\":"+minimum+",\"sessionIdleMinutes\":5,\"primaryColour\":\""+colour+"\",\"currencyDisplay\":\"CODE\",\"logoDataUrl\":"+logo+",\"removeLogo\":false}";}
 @Test void publicBrandingExposesOnlyPresentationAndProtectedRoutesRejectUnsignedAccess() throws Exception {
  mvc.perform(get("/api/v1/organization/branding")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.organisationName").exists()).andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.sessionIdleMinutes").doesNotExist());
  for(String path:new String[]{"/api/v1/organization","/api/v1/organization/operations","/api/v1/retention","/api/v1/investment-corrections"})mvc.perform(get(path)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/docs")).andExpect(status().isOk()).andExpect(jsonPath("$.openapi").value("3.0.3"));
 }
 @Test void policyRequiresAdminAndCsrfAndRejectsWeakPasswordsLowContrastAndInvalidLogo() throws Exception {
  var admin=account("ADMIN");var cookies=new Cookie[]{new Cookie("cu_access",admin.access())};
  mvc.perform(post("/api/v1/organization").cookie(cookies).contentType("application/json").content(body(16,"#083a4f","null"))).andExpect(status().isForbidden());
  for(String invalid:new String[]{body(11,"#083a4f","null"),body(16,"#ffffff","null"),body(16,"#083a4f","\"data:image/png;base64,SGVsbG8=\"")})
   mvc.perform(post("/api/v1/organization").cookie(cookies).header("X-CSRF-Token",admin.csrf()).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
  mvc.perform(post("/api/v1/organization").cookie(cookies).header("X-CSRF-Token",admin.csrf()).contentType("application/json").content(body(16,"#083a4f","null"))).andExpect(status().isOk()).andExpect(jsonPath("$.passwordMinLength").value(16));
  var member=account("MEMBER");mvc.perform(get("/api/v1/organization").cookie(new Cookie("cu_access",member.access()))).andExpect(status().isForbidden());
 }
}
