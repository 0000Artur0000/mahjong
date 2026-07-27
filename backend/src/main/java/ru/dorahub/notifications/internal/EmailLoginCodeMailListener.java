package ru.dorahub.notifications.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.dorahub.accounts.EmailLoginCodeIssued;

@Component
@Profile("prod")
class EmailLoginCodeMailListener {

  private final JavaMailSender mailSender;
  private final String from;

  EmailLoginCodeMailListener(
      JavaMailSender mailSender, @Value("${dorahub.integrations.smtp.from}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void send(EmailLoginCodeIssued event) {
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(event.email());
    message.setSubject("Dorahub login code");
    message.setText(
        "Your Dorahub login code is %s. It expires at %s."
            .formatted(event.code(), event.expiresAt()));
    mailSender.send(message);
  }
}
