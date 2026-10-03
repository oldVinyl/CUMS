package org.harbourcu.payment;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
class PaystackProviderTest {
 @Test void signatureUsesExactRawBytesAndRejectsTampering() throws Exception {
  String secret="sk_test_fixture";var provider=new PaystackProvider(secret,"http://localhost:5173",new ObjectMapper());
  byte[] bytes="{\"event\":\"charge.success\",\"data\":{\"reference\":\"CU-test-123\"}}".getBytes(StandardCharsets.UTF_8);
  Mac mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));String signature=HexFormat.of().formatHex(mac.doFinal(bytes));
  assertThat(provider.parseWebhook(bytes,signature)).isEqualTo("CU-test-123");
  assertThatThrownBy(()->provider.parseWebhook((new String(bytes)+" ").getBytes(StandardCharsets.UTF_8),signature)).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->provider.parseWebhook(bytes,"0".repeat(128))).isInstanceOf(SecurityException.class);
  assertThatThrownBy(()->provider.parseWebhook(bytes,null)).isInstanceOf(SecurityException.class);
 }
 @Test void placeholderKeysNeverEnableRealPayments(){assertThat(new PaystackProvider("replace-with-test-secret-if-used","http://localhost:5173",new ObjectMapper()).enabled()).isFalse();assertThat(new PaystackProvider(null,"http://localhost:5173",new ObjectMapper()).enabled()).isFalse();}
}
