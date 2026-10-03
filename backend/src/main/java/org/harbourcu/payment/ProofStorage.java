package org.harbourcu.payment;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.harbourcu.role.PermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Private receipts are validated from bytes, normalized and served only after ownership checks. */
@Service
public class ProofStorage {
 public record Proof(UUID id, String contentType, byte[] bytes) {}
 private final Path root; private final JdbcTemplate jdbc; private final PermissionService permissions;
 public ProofStorage(@Value("${APP_UPLOAD_DIR:./private-uploads}") String dir, JdbcTemplate jdbc, PermissionService permissions) {
  this.root=Path.of(dir).toAbsolutePath().normalize(); this.jdbc=jdbc; this.permissions=permissions;
 }
 @Transactional public UUID upload(UUID actor, UUID member, MultipartFile file) {
  ownOrStaff(actor,member,true);
  if(file==null || file.isEmpty() || file.getSize()>10485760) throw new IllegalArgumentException("Choose a JPEG, PNG or PDF receipt up to 10 MB.");
  try {
   byte[] raw=file.getBytes(), clean; String type;
   if(raw.length>=5 && new String(raw,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
    try(var pdf=Loader.loadPDF(raw)) {
     if(pdf.isEncrypted() || pdf.getNumberOfPages()<1 || pdf.getNumberOfPages()>100) throw new IllegalArgumentException("Use an unencrypted PDF with 1–100 pages.");
     if(pdf.getDocumentCatalog().getOpenAction()!=null || pdf.getDocumentCatalog().getNames()!=null || pdf.getDocumentCatalog().getAcroForm()!=null)
      throw new IllegalArgumentException("Use a plain PDF without scripts, embedded files or forms.");
     for(var page:pdf.getPages()) if(!page.getAnnotations().isEmpty() || page.getActions()!=null) throw new IllegalArgumentException("Use a plain PDF without interactive annotations.");
     pdf.setDocumentInformation(new org.apache.pdfbox.pdmodel.PDDocumentInformation()); pdf.getDocumentCatalog().setMetadata(null);
     var out=new ByteArrayOutputStream(); pdf.save(out); clean=out.toByteArray(); type="application/pdf";
    }
   } else {
    boolean png=raw.length>=8 && raw[0]==(byte)137 && raw[1]==80 && raw[2]==78 && raw[3]==71;
    boolean jpg=raw.length>=3 && raw[0]==(byte)255 && raw[1]==(byte)216 && raw[2]==(byte)255;
    if(!png && !jpg) throw new IllegalArgumentException("Only JPEG, PNG and PDF receipts are supported. Convert HEIC to JPEG first.");
    try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
     var readers=ImageIO.getImageReaders(input); if(!readers.hasNext()) throw new IllegalArgumentException("Receipt image is invalid.");
     var reader=readers.next(); try {
      reader.setInput(input); long pixels=(long)reader.getWidth(0)*reader.getHeight(0);
      if(pixels>25000000 || pixels<1) throw new IllegalArgumentException("Receipt image is too large. Use at most 25 megapixels.");
      BufferedImage image=reader.read(0); var out=new ByteArrayOutputStream();
      ImageIO.write(image,png?"png":"jpg",out); clean=out.toByteArray(); type=png?"image/png":"image/jpeg";
     } finally {reader.dispose();}
    }
   }
   if(clean.length>10485760) throw new IllegalArgumentException("Normalized receipt exceeds 10 MB.");
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clean));
   UUID id=UUID.randomUUID(),key=UUID.randomUUID(); Files.createDirectories(root); Path path=root.resolve(key.toString());
   Files.write(path,clean,StandardOpenOption.CREATE_NEW);
   org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
    @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED) try{Files.deleteIfExists(path);}catch(IOException ignored){}}
   });
   jdbc.update("INSERT INTO attachments(id,member_id,uploaded_by,storage_key,sha256,content_type,byte_size) VALUES(?,?,?,?,?,?,?)",id,member,actor,key,hash,type,clean.length);
   return id;
  } catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Receipt could not be stored. Try again.",e);}
 }
 @Transactional public Proof download(UUID actor,UUID id){
  var rows=jdbc.queryForList("SELECT member_id,storage_key,content_type FROM attachments WHERE id=?",id);
  if(rows.isEmpty()) throw new IllegalArgumentException("Receipt not found."); var row=rows.getFirst();
  ownOrStaff(actor,(UUID)row.get("member_id"),false);
  jdbc.update("INSERT INTO audit_log(actor_id,action,entity_type,entity_id) VALUES(?,'FILE_DOWNLOADED','ATTACHMENT',?)",actor,id);
  try{return new Proof(id,(String)row.get("content_type"),Files.readAllBytes(root.resolve(row.get("storage_key").toString())));}
  catch(IOException e){throw new IllegalStateException("Receipt is temporarily unavailable.");}
 }
 public void ownOrStaff(UUID actor,UUID member,boolean writing){
  if(permissions.hasPermission(actor,"PAYMENT_VERIFY") || (!writing && permissions.hasPermission(actor,"APPROVE"))) return;
  if(!permissions.hasPermission(actor,"OWN_DATA_READ") || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND member_id=? AND status='ACTIVE')",Boolean.class,actor,member))) throw new SecurityException("Access denied");
 }
}
