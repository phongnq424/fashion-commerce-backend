package com.fashioncommerce.fashioncommercebackend.auth.otp;

import com.fashioncommerce.fashioncommercebackend.auth.dto.OtpChallengeResponse;
import com.fashioncommerce.fashioncommercebackend.auth.email.RegistrationEmailSender;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

@Service
public class RegistrationOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local expected = redis.call('HGET', KEYS[1], 'hash')
            if not expected then return -1 end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if expected == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            if attempts >= tonumber(ARGV[2]) then redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> DAILY_LIMIT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);
    private static final DefaultRedisScript<Long> STORE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 'hash', ARGV[1], 'attempts', 0)
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RegistrationEmailSender emailSender;
    private final OtpProperties properties;
    private final Clock clock;
    private final byte[] pepper;

    public RegistrationOtpService(StringRedisTemplate redis, RegistrationEmailSender emailSender, OtpProperties properties, Clock clock) {
        this.redis = redis;
        this.emailSender = emailSender;
        this.properties = properties;
        this.clock = clock;
        this.pepper = properties.pepper();
    }

    public OtpChallengeResponse request(String email, boolean deliver) {
        return request(email, "register", deliver,
                code -> emailSender.sendRegistrationOtp(email, code, properties.ttl()));
    }

    public OtpChallengeResponse requestPasswordReset(String email, boolean deliver) {
        return request(email, "password-reset", deliver,
                code -> emailSender.sendPasswordResetOtp(email, code, properties.ttl()));
    }

    private OtpChallengeResponse request(String subject, String purpose, boolean deliver,
                                         Consumer<String> delivery) {
        try {
            return requestUsingRedis(subject, purpose, deliver, delivery);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private OtpChallengeResponse requestUsingRedis(String value, String purpose, boolean deliver,
                                                   Consumer<String> delivery) {
        String subject = subject(value);
        String prefix = "auth:otp:" + purpose;
        String cooldownKey = prefix + ":cooldown:" + subject;
        Boolean acquired = redis.opsForValue().setIfAbsent(cooldownKey, "1", properties.resendCooldown());
        if (!Boolean.TRUE.equals(acquired)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_OTP_RESEND_TOO_SOON", "Please wait before requesting another OTP");
        }

        String dailyKey = prefix + ":daily:" + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + ":" + subject;
        Long count = redis.execute(DAILY_LIMIT_SCRIPT, List.of(dailyKey), Long.toString(java.time.Duration.ofDays(2).toSeconds()));
        if (count == null || count > properties.dailySendLimit()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AUTH_OTP_DAILY_LIMIT", "OTP request limit exceeded");
        }

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        String challengeKey = prefix + ":challenge:" + subject;
        redis.execute(STORE_SCRIPT, List.of(challengeKey), digest(purpose, value, code),
                Long.toString(properties.ttl().toMillis()));
        try {
            if (deliver) delivery.accept(code);
        } catch (RuntimeException exception) {
            redis.delete(List.of(challengeKey, cooldownKey));
            throw exception;
        }
        return new OtpChallengeResponse(properties.ttl().toSeconds(), properties.resendCooldown().toSeconds());
    }

    public void verifyAndConsume(String email, String code) {
        verifyAndConsume(email, code, "register");
    }

    public void verifyPasswordResetAndConsume(String email, String code) {
        verifyAndConsume(email, code, "password-reset");
    }

    private void verifyAndConsume(String value, String code, String purpose) {
        try {
            String key = "auth:otp:" + purpose + ":challenge:" + subject(value);
            Long result = redis.execute(VERIFY_SCRIPT, List.of(key), digest(purpose, value, code),
                    Integer.toString(properties.maxAttempts()));
            if (result == null || result != 1L) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_OTP_INVALID_OR_EXPIRED", "OTP is invalid or expired");
            }
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_OTP_TEMPORARILY_UNAVAILABLE", "OTP service is temporarily unavailable");
    }

    private String digest(String purpose, String value, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose + ":" + value + ":" + code)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private String subject(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
