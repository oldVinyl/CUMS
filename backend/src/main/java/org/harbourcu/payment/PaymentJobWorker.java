package org.harbourcu.payment;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration @EnableScheduling public class PaymentJobWorker {
 private final OnlinePaymentService payments;public PaymentJobWorker(OnlinePaymentService payments){this.payments=payments;}
 @Scheduled(fixedDelay=3000,initialDelay=10000) public void process(){payments.processJobs();}
}
