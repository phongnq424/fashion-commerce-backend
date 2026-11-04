# Gmail registration OTP setup

Registration OTPs are delivered to the email address being verified. Phone is still required in the final
registration request, but no OTP is sent to it.

Password-reset OTPs use the same Gmail and Redis configuration. A user may start password reset with either
their email or phone number, but the OTP is always delivered to the email stored on the account.

1. Enable two-step verification on the Gmail account used by the application.
2. Create a Google App Password.
3. Set `GMAIL_USERNAME` to the Gmail address and `GMAIL_APP_PASSWORD` to that App Password.
4. Optionally set `GMAIL_FROM`; otherwise it defaults to `GMAIL_USERNAME`.
5. Configure Redis through `REDIS_URL` because OTP challenges, cooldowns, and attempt counts are stored there.

Never commit the Gmail App Password or a real OTP. Production should use a dedicated sender account.
