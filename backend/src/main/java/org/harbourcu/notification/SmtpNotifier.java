package org.harbourcu.notification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(name="APP_MAIL_ENABLED",havingValue="true")
public class SmtpNotifier implements Notifier {
 private final JavaMailSenderImpl mail=new JavaMailSenderImpl();private final String from;
 public SmtpNotifier(@Value("${SMTP_HOST:localhost}")String host,@Value("${SMTP_PORT:1025}")int port,@Value("${SMTP_USER:}")String user,@Value("${SMTP_PASSWORD:}")String password,@Value("${SMTP_FROM:credit-union@localhost}")String from,@Value("${SMTP_STARTTLS:false}")boolean tls){
  this.from=from;mail.setHost(host);mail.setPort(port);mail.setUsername(user);mail.setPassword(password);var p=mail.getJavaMailProperties();p.setProperty("mail.smtp.auth",Boolean.toString(!user.isBlank()));p.setProperty("mail.smtp.starttls.enable",Boolean.toString(tls));p.setProperty("mail.smtp.starttls.required",Boolean.toString(tls));p.setProperty("mail.smtp.connectiontimeout","5000");p.setProperty("mail.smtp.timeout","5000");p.setProperty("mail.smtp.writetimeout","5000");
 }
 public void send(String recipient,String subject,String text){var m=new SimpleMailMessage();m.setFrom(from);m.setTo(recipient);m.setSubject(subject);m.setText(text);mail.send(m);}
}
