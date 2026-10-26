package com.fashioncommerce.fashioncommercebackend.auth.service;

import com.fashioncommerce.fashioncommercebackend.auth.dto.*;
import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.security.config.SecurityProperties;
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
    private final AuthorizationRepository authorization;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final SecurityProperties properties;
    private final Clock clock;
    private final String dummyHash;

    public AuthenticationService(UserRepository users, UserCredentialRepository credentials, UserSessionRepository sessions,
                                 AuthorizationRepository authorization, PasswordEncoder passwords, TokenService tokens,
                                 SecurityProperties properties, Clock clock) {
        this.users = users; this.credentials = credentials; this.sessions = sessions; this.authorization = authorization;
        this.passwords = passwords; this.tokens = tokens; this.properties = properties; this.clock = clock;
        dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmailIgnoreCase(email)) throw new ApiException(HttpStatus.CONFLICT, "AUTH_EMAIL_EXISTS", "Email already registered");
        if (request.phone() != null && users.existsByPhone(request.phone())) {
            throw new ApiException(HttpStatus.CONFLICT, "AUTH_PHONE_EXISTS", "Phone already registered");
        }
        UUID roleId = authorization.customerRoleId().orElseThrow(() -> new IllegalStateException("Missing PLATFORM CUSTOMER seed role"));
        Instant now = clock.instant();
        User user = users.saveAndFlush(new User(UUID.randomUUID(), email, request.phone(), request.displayName().strip(), now));
        credentials.saveAndFlush(new UserCredential(user.getId(), passwords.encode(request.password()), now));
        authorization.assignCustomer(user.getId(), roleId);
        // ix_users_3/ix_users_4 resolve races; the advice translates violations after transaction rollback.
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getStatus(), user.getEmailVerifiedAt(), authorization.platformRoles(user.getId()));
    }

    @Transactional(noRollbackFor = InvalidCredentialsException.class)
    public TokenResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) throw new InvalidCredentialsException();
        User user = users.lockByEmail(normalizeEmail(request.email())).orElse(null);
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
    public void logout(UUID authenticatedUserId, RefreshRequest request) {
        UserSession session = validSession(request.refreshToken());
        if (!session.getUserId().equals(authenticatedUserId)) throw invalidRefresh();
        session.revoke(clock.instant());
    }

    private UserSession validSession(String refreshToken) {
        return sessions.lockByHash(tokens.hash(refreshToken)).filter(session -> session.isValid(clock.instant()))
                .orElseThrow(AuthenticationService::invalidRefresh);
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN", "Invalid refresh token");
    }
}
