package com.fashioncommerce.fashioncommercebackend.auth.service;

import com.fashioncommerce.fashioncommercebackend.auth.dto.*;
import com.fashioncommerce.fashioncommercebackend.auth.otp.RegistrationOtpService;
import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.security.config.SecurityProperties;
import com.fashioncommerce.fashioncommercebackend.security.jwt.RevokedAccessTokenRepository;
import com.fashioncommerce.fashioncommercebackend.user.entity.*;
import com.fashioncommerce.fashioncommercebackend.user.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class AuthenticationService {
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final UserSessionRepository sessions;
    private final RevokedAccessTokenRepository revokedAccessTokens;
    private final AuthorizationRepository authorization;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final SecurityProperties properties;
    private final RegistrationOtpService registrationOtps;
    private final Clock clock;
    private final String dummyHash;

    public AuthenticationService(UserRepository users, UserCredentialRepository credentials, UserSessionRepository sessions,
                                 RevokedAccessTokenRepository revokedAccessTokens,
                                 AuthorizationRepository authorization, PasswordEncoder passwords, TokenService tokens,
                                 SecurityProperties properties, RegistrationOtpService registrationOtps, Clock clock) {
        this.users = users; this.credentials = credentials; this.sessions = sessions;
        this.revokedAccessTokens = revokedAccessTokens; this.authorization = authorization;
        this.passwords = passwords; this.tokens = tokens; this.properties = properties;
        this.registrationOtps = registrationOtps; this.clock = clock;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    public OtpChallengeResponse requestRegistrationOtp(RegistrationOtpRequest request) {
        String email = normalizeEmail(request.email());
        // Return the same response shape for existing addresses to reduce account enumeration.
        return registrationOtps.request(email, !users.existsByEmailIgnoreCase(email));
    }

    public OtpChallengeResponse requestPasswordResetOtp(PasswordResetOtpRequest request) {
        User user = findForPasswordResetRequest(request.identifier());
        boolean deliver = user != null && user.isActive();
        String subject = passwordResetSubject(deliver ? user : null, request.identifier());
        // Keep the response identical for existing and unknown accounts.
        return registrationOtps.requestPasswordReset(subject, deliver);
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String phone = PhoneNumberNormalizer.normalizeVietnameseMobile(request.phone());
        UUID roleId = authorization.customerRoleId().orElseThrow(() -> new IllegalStateException("Missing PLATFORM CUSTOMER seed role"));
        // Validate possession before revealing whether either identifier already exists.
        registrationOtps.verifyAndConsume(email, request.otp());
        if (users.existsByEmailIgnoreCase(email)) throw new ApiException(HttpStatus.CONFLICT, "AUTH_EMAIL_EXISTS", "Email already registered");
        if (users.existsByPhone(phone)) {
            throw new ApiException(HttpStatus.CONFLICT, "AUTH_PHONE_EXISTS", "Phone already registered");
        }
        Instant now = clock.instant();
        User user = users.saveAndFlush(new User(UUID.randomUUID(), email, phone, request.displayName().strip(), now, now));
        credentials.saveAndFlush(new UserCredential(user.getId(), passwords.encode(request.password()), now));
        authorization.assignCustomer(user.getId(), roleId);
        // ix_users_3/ix_users_4 resolve races; the advice translates violations after transaction rollback.
        return response(user);
    }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public TokenResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw new InvalidCredentialsException();
        User user = findForLogin(request.identifier());
        UserCredential credential = user == null ? null : credentials.findById(user.getId()).orElse(null);
        boolean matches = passwords.matches(request.password(), credential == null ? dummyHash : credential.getPasswordHash());
        Instant now = clock.instant();
        if (user == null || credential == null || !user.isActive() || credential.isLocked(now)) throw new InvalidCredentialsException();
        if (!matches) {
            credential.failed(now);
            throw new InvalidCredentialsException();
        }
        credential.succeeded(now);
        user.loggedIn(now);
        String refresh = tokens.newRefreshToken();
        UserSession session = new UserSession(UUID.randomUUID(), user.getId(), tokens.hash(refresh), now, now.plus(properties.refreshTtl()));
        sessions.saveAndFlush(session);
        return tokens.issue(user.getId(), refresh, session.getExpiresAt());
    }

    @Transactional
    public void resetPassword(PasswordResetRequest request) {
        User user = findForLogin(request.identifier());
        String subject = passwordResetSubject(user != null && user.isActive() ? user : null, request.identifier());
        registrationOtps.verifyPasswordResetAndConsume(subject, request.otp());
        if (user == null || !user.isActive()) throw invalidPasswordResetOtp();

        UserCredential credential = credentials.findById(user.getId()).orElseThrow(AuthenticationService::invalidPasswordResetOtp);
        Instant now = clock.instant();
        credential.changePassword(passwords.encode(request.newPassword()), now);
        sessions.revokeAllByUserId(user.getId(), now);
    }

    @Transactional
    public TokenResponse refresh(RefreshRequest request) {
        UserSession session = validSession(request.refreshToken());
        User user = users.lockById(session.getUserId()).filter(User::isActive).orElseThrow(AuthenticationService::invalidRefresh);
        if (!session.isValid(clock.instant())) throw invalidRefresh();
        String refresh = tokens.newRefreshToken();
        session.rotate(tokens.hash(refresh), clock.instant());
        // Row lock covers validation, hash replacement and token issuance until commit.
        return tokens.issue(user.getId(), refresh, session.getExpiresAt());
    }

    @Transactional
    public void logout(UUID authenticatedUserId, UUID accessTokenId, Instant accessTokenExpiresAt,
                       RefreshRequest request) {
        UserSession session = validSession(request.refreshToken());
        if (!session.getUserId().equals(authenticatedUserId)) throw invalidRefresh();
        Instant now = clock.instant();
        if (!accessTokenExpiresAt.isAfter(now)) throw new ApiException(HttpStatus.UNAUTHORIZED,
                "AUTH_INVALID_ACCESS_TOKEN", "Invalid access token");
        session.revoke(now);
        revokedAccessTokens.revoke(accessTokenId, authenticatedUserId, accessTokenExpiresAt, now);
    }

    private UserSession validSession(String refreshToken) {
        return sessions.lockByHash(tokens.hash(refreshToken)).filter(session -> session.isValid(clock.instant()))
                .orElseThrow(AuthenticationService::invalidRefresh);
    }

    private UserResponse response(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getPhone(), user.getDisplayName(), user.getStatus(),
                user.getEmailVerifiedAt(), authorization.platformRoles(user.getId()));
    }

    private User findForLogin(String identifier) {
        String value = identifier.strip();
        if (value.contains("@")) {
            return users.lockByEmail(normalizeEmail(value)).orElse(null);
        }
        try {
            String phone = PhoneNumberNormalizer.normalizeVietnameseMobile(value);
            return users.lockByPhone(phone).orElse(null);
        } catch (ApiException ignored) {
            return null;
        }
    }

    private User findForPasswordResetRequest(String identifier) {
        String value = identifier.strip();
        if (value.contains("@")) {
            return users.findByEmailIgnoreCase(normalizeEmail(value)).orElse(null);
        }
        try {
            String phone = PhoneNumberNormalizer.normalizeVietnameseMobile(value);
            return users.findByPhone(phone).orElse(null);
        } catch (ApiException ignored) {
            return null;
        }
    }

    private String passwordResetSubject(User user, String identifier) {
        if (user != null) return normalizeEmail(user.getEmail());
        String value = identifier.strip();
        if (value.contains("@")) return normalizeEmail(value);
        try {
            return PhoneNumberNormalizer.normalizeVietnameseMobile(value);
        } catch (ApiException ignored) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private static ApiException invalidPasswordResetOtp() {
        return new ApiException(HttpStatus.BAD_REQUEST, "AUTH_OTP_INVALID_OR_EXPIRED", "OTP is invalid or expired");
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN", "Invalid refresh token");
    }
}
