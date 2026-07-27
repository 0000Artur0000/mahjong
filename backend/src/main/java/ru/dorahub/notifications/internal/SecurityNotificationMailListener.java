package ru.dorahub.notifications.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.dorahub.accounts.AccountIdentityChanged;

@Component
@Profile("prod")
class SecurityNotificationMailListener {

  private final JavaMailSender mailSender;
  private final String from;

  SecurityNotificationMailListener(
      JavaMailSender mailSender, @Value("${dorahub.integrations.smtp.from}") String from) {
    this.mailSender = mailSender;
    this.from = from;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void send(AccountIdentityChanged event) {
    if (event.notificationEmail() == null) {
      return;
    }
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(event.notificationEmail());
    message.setSubject("Dorahub security notification");
    message.setText(
        "A %s login method was %s on your Dorahub account at %s."
            .formatted(event.provider(), event.action(), event.occurredAt()));
    mailSender.send(message);
  }
}
