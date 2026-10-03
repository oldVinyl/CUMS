package org.harbourcu.payment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
@Configuration public class PaymentProviderConfig {
 @Bean PaymentProvider paymentProvider(Environment env,ObjectMapper json){
  String choice=env.getProperty("PAYMENT_PROVIDER",env.acceptsProfiles(Profiles.of("dev"))?"fake":"paystack");
  if(choice.equalsIgnoreCase("fake")){if(!env.acceptsProfiles(Profiles.of("dev")))throw new IllegalStateException("Fake payments require the dev profile.");return new FakeProvider();}
  if(!choice.equalsIgnoreCase("paystack"))throw new IllegalStateException("Choose fake (dev only) or paystack.");
  return new PaystackProvider(env.getProperty("PAYSTACK_SECRET_KEY"),env.getProperty("APP_ALLOWED_ORIGIN","http://localhost:5173"),json);
 }
}
