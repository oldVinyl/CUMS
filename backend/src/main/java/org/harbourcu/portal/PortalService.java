package org.harbourcu.portal;
import java.math.*;
import java.time.*;
import java.util.*;
import org.harbourcu.role.PermissionService;
import org.harbourcu.common.FieldEncryption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @ConditionalOnBean(FieldEncryption.class) public class PortalService {
 public record Figures(String principal,String income,String equity){}
 public record Trend(LocalDate date,String equity){}
 public record Transaction(UUID id,String type,String component,String amount,LocalDate effectiveDate,String reference,String narration){}
 public record Allocation(UUID investmentId,String institution,String tenor,String status,LocalDate purchaseDate,LocalDate maturityDate,String participation,String costShare,String expectedIncome,String actualIncome){}
 public record Overview(UUID memberId,String memberNumber,String fullName,LocalDate asAt,Figures balanceBf,Figures ytd,Figures total,String roePercent,String equityRatioPercent,List<Trend> trend,List<Allocation> allocations,boolean summarizedHistory){}
 public record History(List<Transaction> entries,int page,boolean hasNext){}
 public record Profile(long version,String email,String phone,String nextOfKinName,String nextOfKinPhone,String nextOfKinRelationship){}
 private final JdbcTemplate jdbc;private final PermissionService permissions;private final FieldEncryption fields;private final com.fasterxml.jackson.databind.ObjectMapper json;
 public PortalService(JdbcTemplate jdbc,PermissionService permissions,FieldEncryption fields,com.fasterxml.jackson.databind.ObjectMapper json){this.jdbc=jdbc;this.permissions=permissions;this.fields=fields;this.json=json;}
 public UUID member(UUID actor){
  if(!permissions.hasPermission(actor,"OWN_DATA_READ"))throw new SecurityException("Access denied");
  var ids=jdbc.queryForList("SELECT u.member_id FROM users u JOIN members m ON m.id=u.member_id WHERE u.id=? AND u.status='ACTIVE' AND m.status='ACTIVE'",UUID.class,actor);
  if(ids.isEmpty())throw new IllegalStateException("This account is not linked to an active membership.");return ids.getFirst();
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) public Overview overview(UUID actor,LocalDate asAt){
  UUID id=member(actor);date(asAt);LocalDate start=asAt.withDayOfYear(1);var name=jdbc.queryForMap("SELECT member_number,full_name FROM members WHERE id=?",id);
  BigDecimal bp=sum(id,"PRINCIPAL",LocalDate.of(2000,1,1),start.minusDays(1)),bi=sum(id,"INCOME",LocalDate.of(2000,1,1),start.minusDays(1)),yp=sum(id,"PRINCIPAL",start,asAt),yi=sum(id,"INCOME",start,asAt);
  BigDecimal p=bp.add(yp),i=bi.add(yi);BigDecimal union=jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM ledger_entries WHERE effective_date<=?",BigDecimal.class,asAt);
  var trend=jdbc.query("""
   SELECT d::date AS day,coalesce(sum(l.amount),0) AS equity FROM generate_series(?::date,?::date,interval '1 month') d
   LEFT JOIN ledger_entries l ON l.member_id=? AND l.effective_date<=least((d+interval '1 month - 1 day')::date,?::date)
   GROUP BY d ORDER BY d
   """,(r,n)->new Trend(r.getDate("day").toLocalDate().withDayOfMonth(1).plusMonths(1).minusDays(1).isAfter(asAt)?asAt:r.getDate("day").toLocalDate().plusMonths(1).minusDays(1),r.getBigDecimal("equity").toPlainString()),start,asAt,id,asAt);
  var allocations=jdbc.query("""
   SELECT i.id,i.institution,i.tenor,i.status,i.purchase_date,i.maturity_date,a.participation,a.cost_share,a.income_share,ma.actual_income_share
   FROM investment_allocations a JOIN investments i ON i.id=a.investment_id
   LEFT JOIN investment_maturity_allocations ma ON ma.investment_id=i.id AND ma.member_id=a.member_id AND ma.revision=i.revision
   WHERE a.member_id=? AND i.status NOT IN ('DRAFT','REVERSED') AND i.purchase_date<=?
   ORDER BY i.purchase_date DESC,i.id LIMIT 100
   """,(r,n)->new Allocation(r.getObject("id",UUID.class),r.getString("institution"),r.getString("tenor"),r.getString("status"),r.getDate("purchase_date").toLocalDate(),r.getDate("maturity_date").toLocalDate(),r.getBigDecimal("participation").toPlainString(),r.getBigDecimal("cost_share").toPlainString(),r.getBigDecimal("income_share").toPlainString(),r.getString("actual_income_share")),id,asAt);
  boolean summarized=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_entries WHERE member_id=? AND reference LIKE 'LEGACY-%' AND effective_date<=?)",Boolean.class,id,asAt));
  return new Overview(id,(String)name.get("member_number"),(String)name.get("full_name"),asAt,fig(bp,bi),fig(yp,yi),fig(p,i),percent(i,p),percent(p.add(i),union),trend,allocations,summarized);
 }
 public History history(UUID actor,LocalDate asAt,int page){UUID id=member(actor);date(asAt);if(page<0||page>10000)throw new IllegalArgumentException("Invalid page.");
  var rows=jdbc.query("SELECT id,type,component,amount,effective_date,reference,narration FROM ledger_entries WHERE member_id=? AND effective_date<=? ORDER BY effective_date DESC,recorded_at DESC,id DESC LIMIT 51 OFFSET ?",(r,n)->new Transaction(r.getObject("id",UUID.class),r.getString("type"),r.getString("component"),r.getBigDecimal("amount").toPlainString(),r.getDate("effective_date").toLocalDate(),r.getString("reference"),r.getString("narration")),id,asAt,page*50);
  return new History(rows.size()>50?rows.subList(0,50):rows,page,rows.size()>50);
 }
 @Transactional public Profile profile(UUID actor){UUID id=member(actor);var r=jdbc.queryForMap("SELECT version,email,encrypted_phone,encrypted_next_of_kin FROM members WHERE id=?",id);String kin=fields.decrypt((byte[])r.get("encrypted_next_of_kin"));var k=json.createObjectNode();if(kin!=null)try{k=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(kin);}catch(java.io.IOException e){throw new IllegalStateException("Profile is temporarily unavailable.");}
  jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'OWN_PROFILE_VIEWED','MEMBER',?)",actor,id);
  return new Profile(((Number)r.get("version")).longValue(),(String)r.get("email"),fields.decrypt((byte[])r.get("encrypted_phone")),k.path("name").asText(null),k.path("phone").asText(null),k.path("relationship").asText(null));
 }
 @Transactional public void update(UUID actor,Profile b){UUID id=member(actor);String email=optional(b.email(),320),phone=optional(b.phone(),40),name=optional(b.nextOfKinName(),200),kinPhone=optional(b.nextOfKinPhone(),40),rel=optional(b.nextOfKinRelationship(),80);
  if(email!=null&&!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw new IllegalArgumentException("Enter a valid contact email.");
  var kin=json.createObjectNode().put("name",name).put("phone",kinPhone).put("relationship",rel);
  int changed=jdbc.update("UPDATE members SET email=?,encrypted_phone=?,phone_blind_index=?,encrypted_next_of_kin=?,version=version+1,updated_at=now() WHERE id=? AND version=?",email,fields.encrypt(phone),phone==null?null:fields.blindIndex("member-phone",phone.replaceAll("[^0-9+]","")),fields.encrypt(kin.toString()),id,b.version());
  if(changed!=1)throw new IllegalStateException("Profile changed since you opened it. Reload before saving.");
  jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'OWN_PROFILE_UPDATED','MEMBER',?)",actor,id);
 }
 private BigDecimal sum(UUID id,String component,LocalDate from,LocalDate to){return jdbc.queryForObject("SELECT coalesce(sum(amount),0) FROM ledger_entries WHERE member_id=? AND component=? AND effective_date BETWEEN ? AND ?",BigDecimal.class,id,component,from,to);}
 private static Figures fig(BigDecimal p,BigDecimal i){return new Figures(p.toPlainString(),i.toPlainString(),p.add(i).toPlainString());}
 private static String percent(BigDecimal n,BigDecimal d){return d.signum()==0?"0.00":n.multiply(new BigDecimal("100")).divide(d,2,RoundingMode.HALF_UP).toPlainString();}
 private static String optional(String text,int max){if(text==null||text.isBlank())return null;if(text.length()>max)throw new IllegalArgumentException("Profile field is too long.");return text.trim();}
 private static void date(LocalDate d){if(d==null||d.isBefore(LocalDate.of(2000,1,1))||d.isAfter(LocalDate.now(ZoneId.of("Africa/Accra"))))throw new IllegalArgumentException("Choose a date no later than today.");}
}
