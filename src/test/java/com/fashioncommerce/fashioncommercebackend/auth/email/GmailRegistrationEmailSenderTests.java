package com.fashioncommerce.fashioncommercebackend.auth.email;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GmailRegistrationEmailSenderTests {
    private final JavaMailSender mail = mock(JavaMailSender.class);

    @Test
    void sendsBrandedHtmlOtpWithConfiguredTtl() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(message);
        var sender = new GmailRegistrationEmailSender(mail, "sender@gmail.com");

        sender.sendRegistrationOtp("customer@example.com", "123456", Duration.ofMinutes(7));

        verify(mail).send(message);
        message.saveChanges();
        assertThat(message.getFrom()[0].toString()).contains("Fashion Commerce", "sender@gmail.com");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("customer@example.com");
        assertThat(message.getSubject()).isEqualTo("Mã xác minh Fashion Commerce");
        assertThat(message.getContentType()).startsWith("text/html");
        assertThat(message.getContent().toString()).contains("123456", "7 phút", "Không chia sẻ mã này");
    }

    @Test
    void sendsPasswordResetOtpWithDedicatedSubjectAndContent() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(message);
        var sender = new GmailRegistrationEmailSender(mail, "sender@gmail.com");

        sender.sendPasswordResetOtp("customer@example.com", "654321", Duration.ofMinutes(5));

        verify(mail).send(message);
        message.saveChanges();
        assertThat(message.getSubject()).isEqualTo("Mã OTP đặt lại mật khẩu Fashion Commerce");
        assertThat(message.getContentType()).startsWith("text/html");
        assertThat(message.getContent().toString()).contains("654321", "Đặt lại mật khẩu", "5 phút");
    }

    @Test
    void rejectsMissingConfigurationAndSanitizesDeliveryFailure() {
        assertThatThrownBy(() -> new GmailRegistrationEmailSender(mail, "")
                .sendRegistrationOtp("customer@example.com", "123456", Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class)
                .hasMessage("Email provider is not configured");

        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("private SMTP details")).when(mail).send(any(MimeMessage.class));
        assertThatThrownBy(() -> new GmailRegistrationEmailSender(mail, "sender@gmail.com")
                .sendRegistrationOtp("customer@example.com", "123456", Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class)
                .hasMessage("Unable to deliver OTP");
    }
}
