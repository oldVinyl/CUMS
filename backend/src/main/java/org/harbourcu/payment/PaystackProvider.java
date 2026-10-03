package org.harbourcu.payment;
import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class PaystackProvider implements PaymentProvider {
 private final String secret,origin;private final ObjectMapper json;private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 public PaystackProvider(String secret,String origin,ObjectMapper json){this.secret=secret;this.origin=origin;this.json=json;}
 public String name(){return "PAYSTACK";}public boolean enabled(){return secret!=null&&secret.matches("sk_(test|live)_[A-Za-z0-9]+")&&!secret.contains("replace");}
 public Checkout initiate(Initiation i){
  var b=json.createObjectNode().put("email",i.email()).put("amount",i.amount().movePointRight(2).longValueExact()).put("currency","GHS").put("reference",i.reference()).put("callback_url",origin+"/payments");
  b.putObject("metadata").put("memberId",i.memberId().toString());b.putArray("channels").add(i.method().equals("CARD")?"card":"mobile_money");
  var data=call("/transaction/initialize",b);String url=data.path("authorization_url").asText();URI uri=URI.create(url);
  if(!"https".equals(uri.getScheme())||!"checkout.paystack.com".equals(uri.getHost())||uri.getUserInfo()!=null)throw new IllegalStateException("Provider returned an invalid checkout destination.");
  return new Checkout(data.path("reference").asText(i.reference()),url);
 }
 public Verification verify(String reference){
  var d=call("/transaction/verify/"+URLEncoder.encode(reference,StandardCharsets.UTF_8),null);
  String status=switch(d.path("status").asText()){case "success"->"CONFIRMED";case "failed"->"FAILED";case "abandoned"->"ABANDONED";default->"PENDING";};
  UUID member;try{member=UUID.fromString(d.path("metadata").path("memberId").asText());}catch(IllegalArgumentException e){throw new IllegalStateException("Provider member confirmation is missing.");}
  Instant paid=null;if(status.equals("CONFIRMED"))try{paid=Instant.parse(d.path("paid_at").asText());}catch(DateTimeException e){throw new IllegalStateException("Provider confirmation date is invalid.");}
  if(!d.path("amount").isIntegralNumber()||!d.path("fees").isIntegralNumber())throw new IllegalStateException("Provider amount or fees are invalid.");
  return new Verification(d.path("reference").asText(),member,BigDecimal.valueOf(d.path("amount").longValue()).movePointLeft(2),d.path("currency").asText(),status,BigDecimal.valueOf(d.path("fees").longValue()).movePointLeft(2),paid);
 }
 public String parseWebhook(byte[] bytes,String signature){
  if(!enabled()||bytes.length>65536||signature==null||!signature.matches("[A-Fa-f0-9]{128}"))throw new SecurityException("Invalid payment signature.");
  try{Mac mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
   if(!MessageDigest.isEqual(mac.doFinal(bytes),HexFormat.of().parseHex(signature)))throw new SecurityException("Invalid payment signature.");
   var event=json.readTree(bytes);if(!event.path("event").asText().equals("charge.success"))return null;String ref=event.path("data").path("reference").asText();if(!ref.matches("[A-Za-z0-9.=-]{1,120}"))throw new IllegalArgumentException("Invalid provider reference.");return ref;
  }catch(SecurityException|IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Invalid webhook.");}
 }
 private JsonNode call(String path,JsonNode body){if(!enabled())throw new IllegalStateException("Online payments are not configured.");
  try{var b=HttpRequest.newBuilder(URI.create("https://api.paystack.co"+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+secret).header("Content-Type","application/json");
   var r=http.send(body==null?b.GET().build():b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
   if(r.statusCode()!=200)throw new IllegalStateException("Payment provider is temporarily unavailable. Check the existing payment before retrying.");var data=json.readTree(r.body());if(!data.path("status").asBoolean()||!data.path("data").isObject())throw new IllegalStateException("Provider could not confirm this request.");return data.path("data");
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Payment provider request interrupted.");}catch(java.io.IOException e){throw new IllegalStateException("Payment provider is temporarily unavailable.");}
 }
}
