package com.fashioncommerce.fashioncommercebackend.auth.email;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class GmailRegistrationEmailSender implements RegistrationEmailSender {
    private final JavaMailSender mailSender;
    private final String from;

    public GmailRegistrationEmailSender(JavaMailSender mailSender,
            @Value("${auth.email.from:}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendRegistrationOtp(String email, String code, Duration ttl) {
        if (!StringUtils.hasText(from)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_EMAIL_NOT_CONFIGURED",
                    "Email provider is not configured");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "Fashion Commerce");
            helper.setTo(email);
            helper.setSubject("Mã xác minh Fashion Commerce");
            helper.setText(html(code, ttl), true);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_OTP_DELIVERY_FAILED",
                    "Unable to deliver OTP");
        }
    }

    @Override
    public void sendPasswordResetOtp(String email, String code, Duration ttl) {
        if (!StringUtils.hasText(from)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_EMAIL_NOT_CONFIGURED",
                    "Email provider is not configured");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "Fashion Commerce");
            helper.setTo(email);
            helper.setSubject("Mã OTP đặt lại mật khẩu Fashion Commerce");
            helper.setText(passwordResetHtml(code, ttl), true);
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_OTP_DELIVERY_FAILED",
                    "Unable to deliver OTP");
        }
    }

    private String html(String code, Duration ttl) {
        long minutes = Math.max(1, (ttl.toSeconds() + 59) / 60);
        return """
                <!doctype html>
                <html lang="vi">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>TrendyLab - Mã xác minh của bạn</title>
                </head>
                <body style="margin:0;padding:24px;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#111827">
                  <table role="presentation" style="width:100%%;border-collapse:collapse">
                    <tr>
                      <td align="center">
                        <table role="presentation" style="width:100%%;max-width:560px;border-collapse:collapse;background:#ffffff;border-radius:16px;box-shadow:0 4px 18px rgba(17,24,39,.08)">
                          <tr>
                            <td style="padding:32px">
                              <div style="text-align:center;font-size:24px;font-weight:700;color:#111827">Fashion Commerce</div>
                              <h1 style="margin:28px 0 12px;font-size:22px;line-height:1.35;text-align:center">Xác minh địa chỉ email</h1>
                              <p style="margin:0;color:#4b5563;font-size:15px;line-height:1.6;text-align:center">
                                Sử dụng mã bên dưới để hoàn tất đăng ký tài khoản của bạn.
                              </p>
                              <div style="margin:28px 0;padding:20px;background:#f9fafb;border:1px solid #e5e7eb;border-radius:12px;text-align:center;font-size:34px;font-weight:700;letter-spacing:10px;color:#111827">
                                %s
                              </div>
                              <p style="margin:0;text-align:center;color:#4b5563;font-size:14px;line-height:1.6">
                                Mã có hiệu lực trong <strong>%d phút</strong> và chỉ sử dụng được một lần.
                              </p>
                              <p style="margin:24px 0 0;padding:14px 16px;background:#fff7ed;border-radius:10px;color:#9a3412;font-size:13px;line-height:1.55">
                                Không chia sẻ mã này với bất kỳ ai. Fashion Commerce sẽ không bao giờ yêu cầu bạn cung cấp mã xác minh.
                              </p>
                              <div style="margin-top:28px;padding-top:20px;border-top:1px solid #e5e7eb;color:#6b7280;font-size:12px;line-height:1.6;text-align:center">
                                Nếu bạn không yêu cầu đăng ký tài khoản, bạn có thể bỏ qua email này.
                              </div>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """
                .formatted(code, minutes);
    }

    private String passwordResetHtml(String code, Duration ttl) {
        long minutes = Math.max(1, (ttl.toSeconds() + 59) / 60);
        return """
                <!doctype html>
                <html lang="vi">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>Đặt lại mật khẩu Fashion Commerce</title>
                </head>
                <body style="margin:0;padding:24px;background:#f3f4f6;font-family:Arial,Helvetica,sans-serif;color:#111827">
                  <table role="presentation" style="width:100%%;border-collapse:collapse">
                    <tr><td align="center">
                      <table role="presentation" style="width:100%%;max-width:560px;border-collapse:collapse;background:#ffffff;border-radius:16px;box-shadow:0 4px 18px rgba(17,24,39,.08)">
                        <tr><td style="padding:32px">
                          <div style="text-align:center;font-size:24px;font-weight:700;color:#111827">Fashion Commerce</div>
                          <h1 style="margin:28px 0 12px;font-size:22px;line-height:1.35;text-align:center">Đặt lại mật khẩu</h1>
                          <p style="margin:0;color:#4b5563;font-size:15px;line-height:1.6;text-align:center">
                            Sử dụng mã OTP bên dưới để đặt lại mật khẩu tài khoản của bạn.
                          </p>
                          <div style="margin:28px 0;padding:20px;background:#f9fafb;border:1px solid #e5e7eb;border-radius:12px;text-align:center;font-size:34px;font-weight:700;letter-spacing:10px;color:#111827">
                            %s
                          </div>
                          <p style="margin:0;text-align:center;color:#4b5563;font-size:14px;line-height:1.6">
                            Mã có hiệu lực trong <strong>%d phút</strong> và chỉ sử dụng được một lần.
                          </p>
                          <p style="margin:24px 0 0;padding:14px 16px;background:#fff7ed;border-radius:10px;color:#9a3412;font-size:13px;line-height:1.55">
                            Không chia sẻ mã này với bất kỳ ai. Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này và giữ nguyên mật khẩu hiện tại.
                          </p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(code, minutes);
    }
}
