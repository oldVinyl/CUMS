package org.harbourcu.payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
/** Installed only in the dev profile or explicitly constructed by a test. No production simulation route. */
public final class FakeProvider implements PaymentProvider {
 private final ConcurrentHashMap<String,Verification> records=new ConcurrentHashMap<>();
 public String name(){return "FAKE";}public boolean enabled(){return true;}
 public Checkout initiate(Initiation i){records.putIfAbsent(i.reference(),new Verification(i.reference(),i.memberId(),i.amount(),"GHS","PENDING",BigDecimal.ZERO,null));return new Checkout(i.reference(),null);}
 public Verification verify(String ref){var v=records.get(ref);if(v==null)throw new IllegalStateException("Development payment expired after restart. Start a new test payment.");return v;}
 public void complete(String ref){records.compute(ref,(key,v)->{if(v==null)throw new IllegalArgumentException("Test payment not found.");return new Verification(v.reference(),v.memberId(),v.amount(),"GHS","CONFIRMED",new BigDecimal("0.25"),Instant.now());});}
 public String parseWebhook(byte[] bytes,String signature){throw new SecurityException("Fake provider does not accept public webhooks.");}
}
