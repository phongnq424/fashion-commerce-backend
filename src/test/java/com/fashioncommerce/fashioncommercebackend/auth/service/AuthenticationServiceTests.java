package com.fashioncommerce.fashioncommercebackend.auth.service;

import com.fashioncommerce.fashioncommercebackend.auth.dto.*;
import com.fashioncommerce.fashioncommercebackend.auth.otp.RegistrationOtpService;
import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.security.config.SecurityProperties;
import com.fashioncommerce.fashioncommercebackend.security.jwt.RevokedAccessTokenRepository;
import com.fashioncommerce.fashioncommercebackend.user.entity.*;
import com.fashioncommerce.fashioncommercebackend.user.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationServiceTests {
    private final UserRepository users = mock(UserRepository.class);
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);
    private final UserSessionRepository sessions = mock(UserSessionRepository.class);
    private final RevokedAccessTokenRepository revokedAccessTokens = mock(RevokedAccessTokenRepository.class);
    private final AuthorizationRepository authorization = mock(AuthorizationRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    private final RegistrationOtpService registrationOtps = mock(RegistrationOtpService.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final Instant now = Instant.parse("2026-10-21T07:00:00Z");
    private final UUID id = UUID.randomUUID();
    private final User user = new User(id, "customer@example.com", "+84901234567", "Customer", now, now);
    private final UserCredential credential = new UserCredential(id, passwords.encode("  StrongPassword123  "), now);
    private AuthenticationService service;
    private UserSession session;

    @BeforeEach void setup() {
        var properties = new SecurityProperties(new SecurityProperties.Jwt("test", "unused", Duration.ofMinutes(15)), Duration.ofDays(14), List.of());
        service = new AuthenticationService(users, credentials, sessions, revokedAccessTokens, authorization,
                passwords, tokens, properties, registrationOtps, Clock.fixed(now, ZoneOffset.UTC));
        session = new UserSession(UUID.randomUUID(), id, "old-hash", now.minusSeconds(20), now.plusSeconds(500));
        lenient().when(tokens.hash("old-token")).thenReturn("old-hash");
        lenient().when(tokens.newRefreshToken()).thenReturn("new-token");
        lenient().when(tokens.hash("new-token")).thenReturn("new-hash");
    }

    @Test void registerCreatesEmailVerifiedUserCredentialAndCustomerWithRequiredPhone() {
        UUID role = UUID.randomUUID();
        when(authorization.customerRoleId()).thenReturn(Optional.of(role));
        when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(authorization.platformRoles(any())).thenReturn(List.of("CUSTOMER"));
        var response = service.register(new RegisterRequest("Customer@EXAMPLE.COM", "0901234567", "  StrongPassword123  ", " Customer ", "123456"));
        assertThat(response.email()).isEqualTo("customer@example.com");
        assertThat(response.phone()).isEqualTo("+84901234567");
        assertThat(response.roles()).containsExactly("CUSTOMER");
        assertThat(response.emailVerifiedAt()).isEqualTo(now);
        verify(authorization).assignCustomer(response.id(), role);
        verify(registrationOtps).verifyAndConsume("customer@example.com", "123456");
        var captor = org.mockito.ArgumentCaptor.forClass(UserCredential.class);
        verify(credentials).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).doesNotContain("StrongPassword");
        assertThat(passwords.matches("  StrongPassword123  ", captor.getValue().getPasswordHash())).isTrue();
        assertThat(passwords.matches("StrongPassword123", captor.getValue().getPasswordHash())).isFalse();
    }

    @Test void rejectsCaseInsensitiveDuplicateBeforeWriting() {
        when(authorization.customerRoleId()).thenReturn(Optional.of(UUID.randomUUID()));
        when(users.existsByEmailIgnoreCase("customer@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("CUSTOMER@EXAMPLE.COM", "0901234567", "StrongPassword123", "A", "123456")))
                .isInstanceOf(ApiException.class).hasMessage("Email already registered");
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(credentials);
    }
    @Test void rejectsDuplicatePhone() {
        when(authorization.customerRoleId()).thenReturn(Optional.of(UUID.randomUUID()));
        when(users.existsByPhone("+84901234567")).thenReturn(true);
        assertThatThrownBy(() -> service.register(new RegisterRequest("a@example.com", "0901234567", "StrongPassword123", "A", "123456"))).isInstanceOf(ApiException.class);
        verify(users, never()).saveAndFlush(any());
    }
    @Test void missingSeedRoleFailsBeforeWriting() {
        when(authorization.customerRoleId()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register(new RegisterRequest("a@example.com", "0901234567", "StrongPassword123", "A", "123456"))).isInstanceOf(IllegalStateException.class);
        verify(users, never()).saveAndFlush(any());
    }
    @Test void otpRequestNormalizesEmailAndDoesNotDeliverForExistingAccount() {
        var accepted = new OtpChallengeResponse(300, 60);
        when(users.existsByEmailIgnoreCase("customer@example.com")).thenReturn(true);
        when(registrationOtps.request("customer@example.com", false)).thenReturn(accepted);
        assertThat(service.requestRegistrationOtp(new RegistrationOtpRequest(" Customer@EXAMPLE.COM "))).isEqualTo(accepted);
        verify(registrationOtps).request("customer@example.com", false);
    }
    @Test void passwordResetOtpRequestAcceptsPhoneAndDeliversToAccountEmail() {
        var accepted = new OtpChallengeResponse(300, 60);
        when(users.findByPhone("+84901234567")).thenReturn(Optional.of(user));
        when(registrationOtps.requestPasswordReset("customer@example.com", true)).thenReturn(accepted);

        assertThat(service.requestPasswordResetOtp(new PasswordResetOtpRequest("0901234567")))
                .isEqualTo(accepted);

        verify(registrationOtps).requestPasswordReset("customer@example.com", true);
    }
    @Test void unknownPasswordResetAccountGetsSameResponseWithoutDelivery() {
        var accepted = new OtpChallengeResponse(300, 60);
        when(registrationOtps.requestPasswordReset("missing@example.com", false)).thenReturn(accepted);

        assertThat(service.requestPasswordResetOtp(new PasswordResetOtpRequest("Missing@EXAMPLE.COM")))
                .isEqualTo(accepted);

        verify(registrationOtps).requestPasswordReset("missing@example.com", false);
    }
    private void knownUser() {
        when(users.lockByEmail("customer@example.com")).thenReturn(Optional.of(user));
        when(credentials.findById(id)).thenReturn(Optional.of(credential));
    }
    @Test void loginUpdatesStateCreatesHashedSessionAndIssuesTokens() {
        knownUser(); credential.failed(now.minusSeconds(10));
        service.login(new LoginRequest("CUSTOMER@example.com", "  StrongPassword123  "));
        assertThat(user.getLastLoginAt()).isEqualTo(now);
        assertThat(credential.getFailedAttempts()).isZero();
        var captor = org.mockito.ArgumentCaptor.forClass(UserSession.class);
        verify(sessions).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRefreshTokenHash()).isEqualTo("new-hash");
        assertThat(captor.getValue().getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(14)));
        verify(tokens).issue(id, "new-token", now.plus(Duration.ofDays(14)));
    }
    @Test void loginAcceptsAndNormalizesPhoneIdentifier() {
        when(users.lockByPhone("+84901234567")).thenReturn(Optional.of(user));
        when(credentials.findById(id)).thenReturn(Optional.of(credential));

        service.login(new LoginRequest("0901234567", "  StrongPassword123  "));

        verify(users).lockByPhone("+84901234567");
        verify(tokens).issue(eq(id), eq("new-token"), any());
    }
    @Test void malformedIdentifierUsesGenericCredentialError() {
        assertThatThrownBy(() -> service.login(new LoginRequest("not-an-email-or-phone", "wrong")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email, phone or password");
        verify(users, never()).lockByPhone(any());
        verify(users, never()).lockByEmail(any());
    }
    @Test void resetPasswordRequiresOtpChangesHashClearsLockAndRevokesSessions() {
        when(users.lockByEmail("customer@example.com")).thenReturn(Optional.of(user));
        when(credentials.findById(id)).thenReturn(Optional.of(credential));
        for (int i = 0; i < 5; i++) credential.failed(now.minusSeconds(1));

        service.resetPassword(new PasswordResetRequest("CUSTOMER@example.com", "123456", "NewStrongPassword456"));

        verify(registrationOtps).verifyPasswordResetAndConsume("customer@example.com", "123456");
        verify(sessions).revokeAllByUserId(id, now);
        assertThat(passwords.matches("NewStrongPassword456", credential.getPasswordHash())).isTrue();
        assertThat(credential.getPasswordChangedAt()).isEqualTo(now);
        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(credential.getLockedUntil()).isNull();
    }
    @Test void wrongPasswordCountsAttemptsAndLocksAfterFive() {
        knownUser();
        for (int i = 0; i < 5; i++) assertThatThrownBy(() -> service.login(new LoginRequest(user.getEmail(), "wrong"))).isInstanceOf(InvalidCredentialsException.class);
        assertThat(credential.getFailedAttempts()).isEqualTo(5);
        assertThat(credential.getLockedUntil()).isEqualTo(now.plusSeconds(900));
        assertThatThrownBy(() -> service.login(new LoginRequest(user.getEmail(), "  StrongPassword123  "))).isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(sessions);
    }
    @Test void expiredLockRestartsAttemptCounter() {
        for (int i=0; i<5; i++) credential.failed(now.minusSeconds(1000));
        credential.failed(now);
        assertThat(credential.getFailedAttempts()).isEqualTo(1);
        assertThat(credential.getLockedUntil()).isNull();
    }
    @Test void suspendedAndMissingUsersHaveSameCredentialError() {
        knownUser(); ReflectionTestUtils.setField(user, "status", "SUSPENDED");
        assertThatThrownBy(() -> service.login(new LoginRequest(user.getEmail(), "  StrongPassword123  "))).isInstanceOf(InvalidCredentialsException.class);
        when(users.lockByEmail(user.getEmail())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login(new LoginRequest(user.getEmail(), "wrong"))).isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(sessions);
    }
    @Test void refreshReplacesHashAndKeepsOriginalExpiration() {
        when(sessions.lockByHash("old-hash")).thenReturn(Optional.of(session));
        when(users.lockById(id)).thenReturn(Optional.of(user));
        service.refresh(new RefreshRequest("old-token"));
        assertThat(session.getRefreshTokenHash()).isEqualTo("new-hash");
        verify(tokens).issue(id, "new-token", session.getExpiresAt());
    }
    @Test void rejectsExpiredRevokedAndAlreadyUsedRefreshTokens() {
        var expired = new UserSession(UUID.randomUUID(), id, "old-hash", now.minusSeconds(100), now);
        when(sessions.lockByHash("old-hash")).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.refresh(new RefreshRequest("old-token"))).isInstanceOf(ApiException.class);
        session.revoke(now); when(sessions.lockByHash("old-hash")).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> service.refresh(new RefreshRequest("old-token"))).isInstanceOf(ApiException.class);
        when(sessions.lockByHash("old-hash")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.refresh(new RefreshRequest("old-token"))).isInstanceOf(ApiException.class);
        verify(tokens, never()).issue(any(), any(), any());
    }
    @Test void suspendedUserCannotRefresh() {
        when(sessions.lockByHash("old-hash")).thenReturn(Optional.of(session));
        when(users.lockById(id)).thenReturn(Optional.of(user)); ReflectionTestUtils.setField(user, "status", "SUSPENDED");
        assertThatThrownBy(() -> service.refresh(new RefreshRequest("old-token"))).isInstanceOf(ApiException.class);
        assertThat(session.getRefreshTokenHash()).isEqualTo("old-hash");
    }
    @Test void logoutRevokesOnlyOwnedSession() {
        UUID tokenId = UUID.randomUUID();
        Instant accessExpiresAt = now.plusSeconds(300);
        when(sessions.lockByHash("old-hash")).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> service.logout(UUID.randomUUID(), tokenId, accessExpiresAt,
                new RefreshRequest("old-token"))).isInstanceOf(ApiException.class);
        assertThat(session.getRevokedAt()).isNull();
        verifyNoInteractions(revokedAccessTokens);
        service.logout(id, tokenId, accessExpiresAt, new RefreshRequest("old-token"));
        assertThat(session.getRevokedAt()).isEqualTo(now);
        verify(revokedAccessTokens).revoke(tokenId, id, accessExpiresAt, now);
        assertThatThrownBy(() -> service.refresh(new RefreshRequest("old-token"))).isInstanceOf(ApiException.class);
    }
}
