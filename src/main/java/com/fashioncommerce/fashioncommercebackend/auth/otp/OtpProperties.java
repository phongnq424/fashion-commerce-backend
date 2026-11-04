package com.fashioncommerce.fashioncommercebackend.auth.otp;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Base64;

@Validated
@ConfigurationProperties("auth.otp")
public record OtpProperties(@NotNull Duration ttl,
                            @NotNull Duration resendCooldown,
                            @Min(1) @Max(10) int maxAttempts,
                            @Min(1) @Max(100) int dailySendLimit,
                            @NotBlank String pepperBase64) {
    public OtpProperties {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) throw new IllegalArgumentException("OTP TTL must be positive");
        if (resendCooldown != null && (resendCooldown.isZero() || resendCooldown.isNegative())) {
            throw new IllegalArgumentException("OTP resend cooldown must be positive");
        }
    }

    public byte[] pepper() {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(pepperBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("OTP_PEPPER_BASE64 must be valid Base64", exception);
        }
        if (decoded.length < 32) throw new IllegalStateException("OTP pepper must contain at least 32 bytes");
        return decoded;
    }
}
