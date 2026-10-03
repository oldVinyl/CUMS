package org.harbourcu.payment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
public interface PaymentProvider {
 record Initiation(String reference,UUID memberId,BigDecimal amount,String email,String method){}
 record Checkout(String reference,String url){}
 record Verification(String reference,UUID memberId,BigDecimal amount,String currency,String status,BigDecimal fee,Instant confirmedAt){}
 String name();boolean enabled();Checkout initiate(Initiation input);Verification verify(String reference);String parseWebhook(byte[] bytes,String signature);
}
