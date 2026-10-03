package org.harbourcu.payment;
import java.math.BigDecimal;
import java.util.UUID;
import org.harbourcu.role.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class FinancialPolicyService {
 public record Policy(String organisationName,String contactEmail,String contactPhone,int requiredApprovals,String feeBearer,String paymentMin,String paymentMax){}
 private final JdbcTemplate jdbc;private final PermissionService permissions;
 public FinancialPolicyService(JdbcTemplate jdbc,PermissionService permissions){this.jdbc=jdbc;this.permissions=permissions;}
 public Policy get(UUID actor){if(permissions.currentPermissions(actor).isEmpty())throw new SecurityException("Access denied");return current();}
 public Policy current(){return jdbc.queryForObject("SELECT * FROM organisation_settings WHERE singleton=true",(r,n)->new Policy(r.getString("organisation_name"),r.getString("contact_email"),r.getString("contact_phone"),r.getInt("required_approvals"),r.getString("fee_bearer"),r.getBigDecimal("payment_min").toPlainString(),r.getBigDecimal("payment_max").toPlainString()));}
 @Transactional public Policy update(UUID actor,Policy p){
  if(!permissions.hasPermission(actor,"SETTINGS_WRITE"))throw new SecurityException("Access denied");
  if(p.organisationName()==null||p.organisationName().isBlank()||p.organisationName().length()>200||p.requiredApprovals()<1||p.requiredApprovals()>3||!java.util.List.of("UNION","MEMBER").contains(p.feeBearer()==null?"":p.feeBearer()))throw new IllegalArgumentException("Union name, 1–3 approvals and fee bearer are required.");
  if((p.contactEmail()!=null&&!p.contactEmail().isBlank()&&!p.contactEmail().matches("[^\\s@]{1,200}@[^\\s@]{1,100}\\.[^\\s@]{1,40}"))||(p.contactPhone()!=null&&p.contactPhone().length()>40))throw new IllegalArgumentException("Enter valid contact details.");
  BigDecimal min=money(p.paymentMin()),max=money(p.paymentMax());if(min.signum()<=0||max.compareTo(min)<0||max.compareTo(new BigDecimal("100000000"))>0)throw new IllegalArgumentException("Payment limits must be positive, ordered and no greater than GHS 100,000,000.");
  jdbc.queryForList("SELECT singleton FROM organisation_settings FOR UPDATE");
  jdbc.update("UPDATE organisation_settings SET organisation_name=?,contact_email=?,contact_phone=?,required_approvals=?,fee_bearer=?,payment_min=?,payment_max=?,updated_at=now(),updated_by=? WHERE singleton=true",p.organisationName().trim(),p.contactEmail(),p.contactPhone(),p.requiredApprovals(),p.feeBearer(),min,max,actor);
  jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,details) VALUES(?,'FINANCIAL_POLICY_UPDATED','SETTINGS',jsonb_build_object('requiredApprovals',?::int,'feeBearer',?::text,'paymentMin',?::text,'paymentMax',?::text))",actor,p.requiredApprovals(),p.feeBearer(),min.toPlainString(),max.toPlainString());return current();
 }
 private BigDecimal money(String text){if(text==null||!text.matches("[0-9]{1,9}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("Enter valid GHS limits with up to two decimals.");return new BigDecimal(text);}
}
