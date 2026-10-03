package org.harbourcu.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.*;
import java.time.*;
import java.util.*;
import org.harbourcu.approval.ApprovalService;
import org.harbourcu.common.FieldEncryption;
import org.harbourcu.ledger.FiscalYearLock;
import org.harbourcu.member.MemberService;
import org.harbourcu.role.PermissionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @ConditionalOnBean(FieldEncryption.class)
public class ImportService {
    public static final List<String> MEMBER_COLUMNS=List.of("member_number","full_name","email","phone","id_type","id_number","date_of_birth","address","next_of_kin_name","next_of_kin_phone","next_of_kin_relationship","join_date");
    public static final List<String> LEDGER_COLUMNS=List.of("member_number","type","component","amount","effective_date","reference","narration");
    public record RowReport(int row,String memberNumber,String name,String amount,String effectiveDate,List<String> errors) {}
    public record Report(String kind,String fileHash,int rowCount,String total,boolean valid,List<String> warnings,List<RowReport> rows) {}
    public record Batch(UUID id,String kind,int rowCount,String total,String status,UUID makerId,String reason,String createdAt,
                        UUID requestId,UUID reversalRequestId,int approvals,int requiredApprovals,
                        UUID reversalMakerId,String reversalStatus,int reversalApprovals,int reversalRequiredApprovals,String reversalReason) {}
    public record Page(List<Batch> batches,boolean hasNext,int page) {}
    private final JdbcTemplate jdbc;private final PermissionService permissions;private final ImportFileParser parser;
    private final FieldEncryption fields;private final ObjectMapper json;private final MemberService members;
    private final FiscalYearLock years;
    // ApprovalService is passed at the controller boundary to keep the handler/service dependency acyclic.
    public ImportService(JdbcTemplate jdbc,PermissionService permissions,ImportFileParser parser,FieldEncryption fields,ObjectMapper json,MemberService members,FiscalYearLock years){
        this.jdbc=jdbc;this.permissions=permissions;this.parser=parser;this.fields=fields;this.json=json;this.members=members;this.years=years;
    }
    public List<String> columns(String kind){return switch(kind){case "MEMBERS"->MEMBER_COLUMNS;case "LEDGER"->LEDGER_COLUMNS;default->throw new IllegalArgumentException("Choose MEMBERS or LEDGER.");};}
    public Report dryRun(UUID actor,String kind,String filename,byte[] bytes){requireMaker(actor,kind);return analyze(kind,hash(bytes),parser.parse(filename,bytes,columns(kind)));}
    @Transactional
    public UUID submit(UUID actor,String kind,String filename,byte[] bytes,String expectedHash,String reason,ApprovalService approvals){
        requireMaker(actor,kind);reason=reason(reason);
        var data=parser.parse(filename,bytes,columns(kind));var report=analyze(kind,hash(bytes),data);
        if(!report.fileHash().equals(expectedHash))throw new IllegalArgumentException("The file changed. Run the dry run again before confirming.");
        jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");
        Boolean duplicate=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM import_batches WHERE kind=? AND file_hash=? AND status IN ('PENDING','POSTED'))",Boolean.class,kind,report.fileHash());
        if(Boolean.TRUE.equals(duplicate))throw new IllegalStateException("This file has already been submitted or posted. Open its existing batch.");
        if(!report.valid())throw new IllegalArgumentException("Resolve every validation error before submitting.");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO import_batches(id,kind,file_hash,row_count,total,made_by,reason) VALUES(?,?,?,?,?,?,?)",id,kind,report.fileHash(),report.rowCount(),new BigDecimal(report.total()),actor,reason);
        int number=0;for(var row:data){if("TOTAL".equals(row.get("member_number")))continue;
            jdbc.update("INSERT INTO import_rows(batch_id,row_number,encrypted_data) VALUES(?,?,?)",id,++number,fields.encrypt(encode(row)));
        }
        UUID request=approvals.submit("IMPORT_POST",json.createObjectNode().put("batchId",id.toString()),actor,1);
        jdbc.update("UPDATE import_batches SET approval_request_id=? WHERE id=?",request,id);audit(actor,id,"IMPORT_SUBMITTED");return id;
    }
    public Page list(UUID actor,int page){requireReader(actor);if(page<0||page>10000)throw new IllegalArgumentException("Choose a valid page.");
        var rows=jdbc.query("""
            SELECT b.*,coalesce((SELECT count(*) FROM approval_decisions d WHERE d.request_id=b.approval_request_id AND d.decision='APPROVE'),0) approvals,
            a.required_approvals,rev.made_by reversal_maker,rev.status reversal_status,rev.payload->>'reason' reversal_reason,coalesce(rev.required_approvals,0) reversal_required,
            coalesce((SELECT count(*) FROM approval_decisions d WHERE d.request_id=b.reversal_request_id AND d.decision='APPROVE'),0) reversal_approvals
            FROM import_batches b JOIN approval_requests a ON a.id=b.approval_request_id LEFT JOIN approval_requests rev ON rev.id=b.reversal_request_id
            ORDER BY b.created_at DESC,b.id DESC LIMIT 26 OFFSET ?
            """,(rs,n)->new Batch(rs.getObject("id",UUID.class),rs.getString("kind"),rs.getInt("row_count"),rs.getBigDecimal("total").toPlainString(),rs.getString("status"),rs.getObject("made_by",UUID.class),rs.getString("reason"),rs.getTimestamp("created_at").toInstant().toString(),rs.getObject("approval_request_id",UUID.class),rs.getObject("reversal_request_id",UUID.class),rs.getInt("approvals"),rs.getInt("required_approvals"),rs.getObject("reversal_maker",UUID.class),rs.getString("reversal_status"),rs.getInt("reversal_approvals"),rs.getInt("reversal_required"),rs.getString("reversal_reason")),page*25);
        return new Page(rows.stream().limit(25).toList(),rows.size()>25,page);
    }
    public Report review(UUID actor,UUID id){requireReader(actor);var batch=batch(id);var data=data(id);
        // Review shows the originally submitted public impact, not a fresh validity claim for posted/reversed rows.
        List<RowReport> rows=new ArrayList<>();int n=0;
        for(var r:data)rows.add(new RowReport(++n,r.get("member_number"),r.get("full_name"),r.get("amount"),r.getOrDefault("effective_date",r.get("join_date")),List.of()));
        audit(actor,id,"IMPORT_REVIEWED");
        return new Report((String)batch.get("kind"),(String)batch.get("file_hash"),data.size(),batch.get("total").toString(),true,List.of("Review the submitted values and reason. Eligibility is checked again at final approval."),rows);
    }
    public void validatePost(UUID actor,UUID id){var b=batch(id);String kind=(String)b.get("kind");requireMaker(actor,kind);
        if(!actor.equals(b.get("made_by"))||!"PENDING".equals(b.get("status")))throw new IllegalStateException("This import is no longer pending for its maker.");
        if(!analyze(kind,(String)b.get("file_hash"),data(id)).valid())throw new IllegalStateException("Import source data changed or no longer validates. Cancel and run a fresh dry run.");
    }
    public void post(UUID request,UUID checker,UUID id){jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");var b=lockedBatch(id);UUID maker=(UUID)b.get("made_by");String kind=(String)b.get("kind");
        var rows=data(id);
        if("LEDGER".equals(kind))for(int year:rows.stream().map(r->LocalDate.parse(r.get("effective_date")).getYear()).distinct().sorted().toList())years.requireOpen(year);
        validatePost(maker,id);
        int n=0;for(var r:rows){n++;if("MEMBERS".equals(kind)){
            UUID member=members.create(maker,new MemberService.CreateMember(empty(r.get("member_number")),r.get("full_name"),empty(r.get("email")),empty(r.get("phone")),empty(r.get("id_type")),empty(r.get("id_number")),date(r.get("date_of_birth")),empty(r.get("address")),empty(r.get("next_of_kin_name")),empty(r.get("next_of_kin_phone")),empty(r.get("next_of_kin_relationship")),date(r.get("join_date"))));
            jdbc.update("INSERT INTO import_member_results(batch_id,row_number,member_id) VALUES(?,?,?)",id,n,member);
        }else{
            UUID member=jdbc.queryForObject("SELECT id FROM members WHERE member_number=?",UUID.class,r.get("member_number"));LocalDate effective=date(r.get("effective_date"));
            jdbc.update("""
                INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference,narration,created_by,approved_by,batch_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,member,r.get("type"),r.get("component"),new BigDecimal(r.get("amount")),effective,effective.getYear(),r.get("reference"),empty(r.get("narration")),maker,checker,id);
        }}
        jdbc.update("UPDATE import_batches SET status='POSTED',posted_at=now() WHERE id=?",id);audit(checker,id,"IMPORT_POSTED");
    }
    @Transactional public UUID reverse(UUID actor,UUID id,String reason,ApprovalService approvals){var b=lockedBatch(id);requireReverse(actor,(String)b.get("kind"));reason=reason(reason);
        if(!"POSTED".equals(b.get("status")))throw new IllegalStateException("Only posted batches can be reversed.");
        if(b.get("reversal_request_id")!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_requests WHERE id=? AND status='PENDING')",Boolean.class,b.get("reversal_request_id"))))throw new IllegalStateException("This batch already has a pending reversal.");
        UUID request=approvals.submit("IMPORT_REVERSE",json.createObjectNode().put("batchId",id.toString()).put("reason",reason),actor,1);
        jdbc.update("UPDATE import_batches SET reversal_request_id=? WHERE id=?",request,id);audit(actor,id,"IMPORT_REVERSAL_SUBMITTED");return request;
    }
    public void validateReverse(UUID actor,UUID id){var b=batch(id);String kind=(String)b.get("kind");requireReverse(actor,kind);
        if(!"POSTED".equals(b.get("status")))throw new IllegalStateException("The batch is no longer posted.");
        if("MEMBERS".equals(kind)){
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM import_member_results r WHERE r.batch_id=? AND
                  (EXISTS(SELECT 1 FROM ledger_entries l WHERE l.member_id=r.member_id) OR EXISTS(SELECT 1 FROM users u WHERE u.member_id=r.member_id)
                   OR EXISTS(SELECT 1 FROM investment_allocations a WHERE a.member_id=r.member_id)))
                """,Boolean.class,id)))throw new IllegalStateException("Imported members now have financial records or accounts. Review them individually instead of reversing this batch.");
        }else{
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_entries l JOIN ledger_entries r ON r.reversal_of=l.id WHERE l.batch_id=?)",Boolean.class,id)))throw new IllegalStateException("A batch entry was already reversed. Review the batch individually.");
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                WITH amounts AS (SELECT member_id,sum(amount) amount FROM ledger_entries WHERE batch_id=? GROUP BY member_id)
                SELECT EXISTS(SELECT 1 FROM amounts a WHERE
                coalesce((SELECT sum(amount) FROM ledger_entries l WHERE l.member_id=a.member_id AND effective_date<=?),0)-a.amount
                  < coalesce((SELECT sum(ia.participation) FROM investment_allocations ia JOIN investments i ON i.id=ia.investment_id WHERE ia.member_id=a.member_id AND i.status='POSTED'),0))
                """,Boolean.class,id,today())))throw new IllegalStateException("Imported funds have been committed or reversing would leave a negative balance.");
        }
    }
    public void reversePost(UUID request,UUID checker,UUID id,String reason){jdbc.execute("SELECT pg_advisory_xact_lock(78124510)");var b=lockedBatch(id);
        UUID maker=jdbc.queryForObject("SELECT made_by FROM approval_requests WHERE id=?",UUID.class,request);validateReverse(maker,id);
        if("MEMBERS".equals(b.get("kind"))){for(UUID member:jdbc.queryForList("SELECT member_id FROM import_member_results WHERE batch_id=? ORDER BY member_id",UUID.class,id)){
            long version=jdbc.queryForObject("SELECT version FROM members WHERE id=?",Long.class,member);members.changeStatus(maker,member,version,"INACTIVE");
        }}else{years.requireOpen(today().getYear());
            jdbc.update("""
                INSERT INTO ledger_entries(member_id,type,component,amount,effective_date,fiscal_year,reference,narration,reversal_of,created_by,approved_by,batch_id,original_effective_date)
                SELECT member_id,'REVERSAL',component,-amount,?,?,'IBR-'||id::text,?,id,?,?,?,effective_date FROM ledger_entries WHERE batch_id=? AND type<>'REVERSAL'
                """,today(),today().getYear(),reason,maker,checker,id,id);
        }
        jdbc.update("UPDATE import_batches SET status='REVERSED',reversed_at=now() WHERE id=?",id);audit(checker,id,"IMPORT_REVERSED");
    }
    @Transactional public void decide(UUID actor,UUID id,UUID request,String decision,String comment,ApprovalService approvals){requireReader(actor);var b=lockedBatch(id);linked(b,request);
        if(!"APPROVE".equals(decision)&&!"REJECT".equals(decision))throw new IllegalArgumentException("Choose APPROVE or REJECT.");
        if(comment!=null&&comment.length()>1000)throw new IllegalArgumentException("Decision comments are limited to 1,000 characters.");
        approvals.decide(request,actor,ApprovalService.Decision.valueOf(decision),comment);
        if(request.equals(b.get("approval_request_id"))&&"REJECTED".equals(jdbc.queryForObject("SELECT status FROM approval_requests WHERE id=?",String.class,request)))jdbc.update("UPDATE import_batches SET status='REJECTED' WHERE id=?",id);
    }
    @Transactional public void cancel(UUID actor,UUID id,UUID request,ApprovalService approvals){var b=lockedBatch(id);linked(b,request);approvals.cancel(request,actor);
        if(request.equals(b.get("approval_request_id")))jdbc.update("UPDATE import_batches SET status='CANCELLED' WHERE id=?",id);
    }
    private void linked(Map<String,Object> b,UUID request){if(request==null||(!request.equals(b.get("approval_request_id"))&&!request.equals(b.get("reversal_request_id"))))throw new IllegalArgumentException("This request does not belong to the batch.");}
    private Report analyze(String kind,String hash,List<Map<String,String>> input){
        var reports=new ArrayList<RowReport>();var warnings=new ArrayList<String>();var numbers=new HashSet<String>();var refs=new HashSet<String>();
        BigDecimal total=BigDecimal.ZERO,declared=null;boolean totalsError=false;int n=0;
        for(var row:input){if("TOTAL".equals(row.get("member_number"))&&"LEDGER".equals(kind)){
            if(declared!=null||row!=input.getLast())totalsError=true;
            try{declared=money(row.get("amount"),4);}catch(IllegalArgumentException e){totalsError=true;}continue;
        }
            var errors=new ArrayList<String>();n++;
            try{if("MEMBERS".equals(kind))validateMember(row,numbers);else validateEntry(row,refs);}catch(IllegalArgumentException|IllegalStateException e){errors.add(e.getMessage());}
            if("LEDGER".equals(kind)&&errors.isEmpty())total=total.add(new BigDecimal(row.get("amount")));
            reports.add(new RowReport(n,row.get("member_number"),row.get("full_name"),row.get("amount"),row.getOrDefault("effective_date",row.get("join_date")),errors));
        }
        if(reports.size()>1000||reports.isEmpty())throw new IllegalArgumentException("Include 1–1,000 data rows.");
        if("LEDGER".equals(kind)){
            if(declared==null&&!totalsError)warnings.add("No TOTAL row was supplied. Independently compare the calculated total with your source before confirming.");
            if(totalsError||(declared!=null&&declared.compareTo(total)!=0)){totalsError=true;warnings.add("The supplied TOTAL does not match the valid data rows.");}
            if(total.precision()-total.scale()>15){totalsError=true;warnings.add("The batch total exceeds the financial storage limit.");}
            warnings.add("Historical dates require open years without later closed snapshots. Closed-period correction and historical investment imports are not supported by this template.");
        }else warnings.add("Blank member numbers are generated at approval. Private fields are encrypted when confirmed; no records are created by this dry run.");
        return new Report(kind,hash,reports.size(),total.toPlainString(),!totalsError&&reports.stream().allMatch(r->r.errors().isEmpty()),warnings,reports);
    }
    private void validateMember(Map<String,String> r,Set<String> seen){
        String number=r.get("member_number");if(!number.isEmpty()){
            if(number.length()>40||!seen.add(number))throw new IllegalArgumentException("Member number is too long or repeated in this file.");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE member_number=?)",Boolean.class,number)))throw new IllegalArgumentException("Member number already exists.");
        }
        for(var e:Map.of("full_name",200,"email",320,"phone",40,"id_type",60,"id_number",120,"next_of_kin_name",200,"next_of_kin_phone",40,"next_of_kin_relationship",80).entrySet())if(r.get(e.getKey()).length()>e.getValue())throw new IllegalArgumentException(e.getKey()+" exceeds its length limit.");
        if(r.get("full_name").isBlank())throw new IllegalArgumentException("Full name is required.");
        if(!r.get("email").isEmpty()&&!r.get("email").matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))throw new IllegalArgumentException("Email is invalid.");
        LocalDate join=date(r.get("join_date")),dob=date(r.get("date_of_birth"));
        if(join==null||join.isAfter(today())||join.isBefore(LocalDate.of(2000,1,1)))throw new IllegalArgumentException("Join date must be between 2000 and today.");
        if(dob!=null&&!dob.isBefore(join))throw new IllegalArgumentException("Date of birth must precede join date.");
        if(!r.get("id_number").isBlank()){
            if(r.get("id_type").isBlank())throw new IllegalArgumentException("ID type is required with an ID number.");
            String digest=fields.blindIndex("member-id",r.get("id_number").toUpperCase(Locale.ROOT));
            if(!seen.add("ID:"+digest)||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE id_number_blind_index=?)",Boolean.class,digest)))throw new IllegalArgumentException("Identity number is already registered or repeated in this file.");
        }
    }
    private void validateEntry(Map<String,String> r,Set<String> seen){
        String type=r.get("type"),component=r.get("component");if(!Set.of("OPENING_BALANCE","SEED","ADDITION").contains(type))throw new IllegalArgumentException("Use OPENING_BALANCE, SEED or ADDITION.");
        if(!Set.of("PRINCIPAL","INCOME").contains(component)||(!"OPENING_BALANCE".equals(type)&&!"PRINCIPAL".equals(component)))throw new IllegalArgumentException("Contributions use PRINCIPAL; opening balances can use PRINCIPAL or INCOME.");
        BigDecimal amount=money(r.get("amount"),"OPENING_BALANCE".equals(type)?4:2);
        if(amount.signum()==0||(amount.signum()<0&&!("OPENING_BALANCE".equals(type)&&"INCOME".equals(component))))throw new IllegalArgumentException("Use nonzero amounts; only opening income can be negative.");
        LocalDate effective=date(r.get("effective_date"));if(effective==null||effective.isAfter(today())||effective.isBefore(LocalDate.of(2000,1,1)))throw new IllegalArgumentException("Effective date must be between 2000 and today.");
        var member=jdbc.queryForList("SELECT join_date,status FROM members WHERE member_number=?",r.get("member_number"));
        if(member.isEmpty()||!"ACTIVE".equals(member.getFirst().get("status")))throw new IllegalArgumentException("Member number must identify an active member.");
        if(effective.isBefore(((java.sql.Date)member.getFirst().get("join_date")).toLocalDate()))throw new IllegalArgumentException("Effective date precedes the member's join date.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fiscal_years WHERE year=? AND status='OPEN')",Boolean.class,effective.getYear())))throw new IllegalArgumentException("The effective fiscal year is closed or missing.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM fiscal_year_snapshots WHERE fiscal_year>=?)",Boolean.class,effective.getYear())))throw new IllegalArgumentException("Later closed snapshots depend on this date. Historical correction is required first.");
        String ref=r.get("reference");if(ref.isBlank()||ref.length()>120||!seen.add(ref))throw new IllegalArgumentException("Reference is required, limited to 120 characters and unique within this file.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ledger_entries WHERE reference=?)",Boolean.class,ref)))throw new IllegalArgumentException("Reference already exists in the ledger.");
        if(r.get("narration").length()>1000)throw new IllegalArgumentException("Narration exceeds 1,000 characters.");
    }
    private BigDecimal money(String v,int scale){if(v==null||!v.matches("-?[0-9]+(\\.[0-9]{1,"+scale+"})?"))throw new IllegalArgumentException("Amount must be a plain decimal with at most "+scale+" places.");BigDecimal n=new BigDecimal(v);if(n.precision()-n.scale()>15)throw new IllegalArgumentException("Amount exceeds the financial storage limit.");return n;}
    private LocalDate date(String v){if(v==null||v.isEmpty())return null;try{return LocalDate.parse(v);}catch(DateTimeException e){throw new IllegalArgumentException("Dates must use YYYY-MM-DD.");}}
    private LocalDate today(){return LocalDate.now(ZoneId.of("Africa/Accra"));}
    private String empty(String v){return v==null||v.isEmpty()?null:v;}
    private String reason(String v){if(v==null||v.isBlank()||v.trim().length()>1000)throw new IllegalArgumentException("A reason of up to 1,000 characters is required.");return v.trim();}
    private void requireMaker(UUID actor,String kind){columns(kind);if(!permissions.hasPermission(actor,"MEMBER_IMPORT")||!permissions.hasPermission(actor,"MEMBERS".equals(kind)?"MEMBER_WRITE":"LEDGER_POST"))throw new SecurityException("Import permission required");
        if("LEDGER".equals(kind)&&!permissions.hasPermission(actor,"LEDGER_BACKDATE"))throw new SecurityException("Historical ledger import permission required");}
    private void requireReverse(UUID actor,String kind){if(!permissions.hasPermission(actor,"MEMBER_IMPORT")||!permissions.hasPermission(actor,"MEMBERS".equals(kind)?"MEMBER_WRITE":"LEDGER_REVERSE"))throw new SecurityException("Batch reversal permission required");}
    private void requireReader(UUID actor){if(!permissions.hasPermission(actor,"MEMBER_IMPORT")&&!permissions.hasPermission(actor,"APPROVE")&&!permissions.hasPermission(actor,"AUDIT_READ"))throw new SecurityException("Import access required");}
    private Map<String,Object> batch(UUID id){var rows=jdbc.queryForList("SELECT * FROM import_batches WHERE id=?",id);if(rows.isEmpty())throw new IllegalArgumentException("Import batch not found.");return rows.getFirst();}
    private Map<String,Object> lockedBatch(UUID id){var rows=jdbc.queryForList("SELECT * FROM import_batches WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new IllegalArgumentException("Import batch not found.");return rows.getFirst();}
    private List<Map<String,String>> data(UUID id){return jdbc.query("SELECT encrypted_data FROM import_rows WHERE batch_id=? ORDER BY row_number",(rs,n)->decode(fields.decrypt(rs.getBytes(1))),id);}
    private String encode(Map<String,String> row){try{return json.writeValueAsString(row);}catch(Exception e){throw new IllegalStateException("Import data could not be stored.");}}
    private Map<String,String> decode(String row){try{return json.readValue(row,new TypeReference<Map<String,String>>(){});}catch(Exception e){throw new IllegalStateException("Import data could not be read.");}}
    private String hash(byte[] data){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void audit(UUID actor,UUID id,String action){jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,?,'IMPORT_BATCH',?)",actor,action,id);}
}
