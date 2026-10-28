package com.fashioncommerce.fashioncommercebackend.security;

import com.fashioncommerce.fashioncommercebackend.auth.controller.AuthenticationController;
import com.fashioncommerce.fashioncommercebackend.auth.dto.*;
import com.fashioncommerce.fashioncommercebackend.auth.service.*;
import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.authorization.service.PlatformAuthorizationService;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiExceptionHandler;
import com.fashioncommerce.fashioncommercebackend.security.config.SecurityConfiguration;
import com.fashioncommerce.fashioncommercebackend.security.exception.SecurityErrorHandler;
import com.fashioncommerce.fashioncommercebackend.security.jwt.JwtConfiguration;
import com.fashioncommerce.fashioncommercebackend.user.controller.CurrentUserController;
import com.fashioncommerce.fashioncommercebackend.user.entity.User;
import com.fashioncommerce.fashioncommercebackend.user.repository.UserRepository;
import com.fashioncommerce.fashioncommercebackend.user.service.CurrentUserService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthenticationController.class, CurrentUserController.class}, properties = {"spring.config.import=", "debug=false"})
@Import({SecurityConfiguration.class, JwtConfiguration.class, SecurityErrorHandler.class, ApiExceptionHandler.class,
        CurrentUserService.class, PlatformAuthorizationService.class})
class SecurityHttpTests {
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @MockitoBean AuthenticationService authentication;
    @MockitoBean UserRepository users;
    @MockitoBean AuthorizationRepository authorization;
    private static final String KEY;
    static { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); KEY = Base64.getEncoder().encodeToString(bytes); }
    private final UUID userId = UUID.randomUUID();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("security.jwt.secret-base64", () -> KEY);
        registry.add("security.jwt.issuer", () -> "test");
        registry.add("security.cors-origins", () -> "https://store.example.com");
    }
    String access(Instant expiration) {
        var claims = JwtClaimsSet.builder().subject(userId.toString()).issuer("test").issuedAt(Instant.now().minusSeconds(120))
                .expiresAt(expiration).id(UUID.randomUUID().toString()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
    }

    @Test void protectedEndpointWithoutTokenReturnsJson401() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_UNAUTHORIZED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer")).andExpect(jsonPath("$.path").value("/api/v1/users/me"));
    }
    @Test void internalErrorDispatchIsNotReplacedByAuthentication401() throws Exception {
        mvc.perform(get("/error")
                        .with(request -> { request.setDispatcherType(DispatcherType.ERROR); return request; })
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/failing-request"))
                .andExpect(status().isInternalServerError());
    }
    @Test void otherEndpointsAndActuatorAreProtected() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/anything")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/login")).andExpect(status().isUnauthorized());
    }
    @Test void invalidAndExpiredAccessTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(Instant.now().minusSeconds(60)))).andExpect(status().isUnauthorized());
    }
    @Test void suspendedAccountIsRejectedEvenWithValidSignature() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(Instant.now().plusSeconds(900)))).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedUserWithoutPermissionGets403() throws Exception {
        when(users.existsByIdAndStatus(userId, "ACTIVE")).thenReturn(true);
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + access(Instant.now().plusSeconds(900))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    @Test void meUsesAuthenticatedIdentityAndDatabaseRoles() throws Exception {
        when(users.existsByIdAndStatus(userId, "ACTIVE")).thenReturn(true);
        when(authorization.hasPlatformPermission(userId, "account.read_own")).thenReturn(true);
        when(users.findById(userId)).thenReturn(Optional.of(new User(userId, "me@example.com", null, "Me", Instant.now())));
        when(authorization.platformRoles(userId)).thenReturn(List.of("CUSTOMER", "PLATFORM_MODERATOR"));
        mvc.perform(get("/api/v1/users/me").param("userId", UUID.randomUUID().toString()).header("Authorization", "Bearer " + access(Instant.now().plusSeconds(900))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.roles[1]").value("PLATFORM_MODERATOR")).andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test void publicRegistrationRejectsInjectedRolesAndInvalidPayloads() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"me@example.com","password":"StrongPassword123","displayName":"Me","role":"PLATFORM_ADMIN"}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"wrong","password":"short","displayName":""}
                """)).andExpect(status().isBadRequest());
        verifyNoInteractions(authentication);
    }
    @Test void validRegistrationIsPublicAndReturns201() throws Exception {
        when(authentication.register(any())).thenReturn(new UserResponse(userId, "me@example.com", "Me", "ACTIVE", null, List.of("CUSTOMER")));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"me@example.com","password":"StrongPassword123","displayName":"Me"}
                """)).andExpect(status().isCreated()).andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }
    @Test void loginErrorsAreGenericAndContainNoSecrets() throws Exception {
        when(authentication.login(any())).thenThrow(new InvalidCredentialsException());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"me@example.com","password":"wrong"}
                """)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }
    @Test void refreshIsPublicButLogoutRequiresBearer() throws Exception {
        String body = "{\"refreshToken\":\"" + "a".repeat(43) + "\"}";
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        when(users.existsByIdAndStatus(userId, "ACTIVE")).thenReturn(true);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + access(Instant.now().plusSeconds(900))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        verify(authentication).logout(eq(userId), any());
    }
    @Test void corsAllowsOnlyConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://store.example.com").header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://store.example.com"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "https://untrusted.example.com").header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
    @Test void databaseUniqueConstraintRaceBecomes409WithoutSqlDetails() throws Exception {
        var constraint = new org.hibernate.exception.ConstraintViolationException("private SQL details", new java.sql.SQLException(), "ix_users_3");
        when(authentication.register(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("private", constraint));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"me@example.com","password":"StrongPassword123","displayName":"Me"}
                """)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("AUTH_EMAIL_EXISTS"))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }
    @Test void unexpectedFailureIsSanitizedAndWrongContentTypeUses415() throws Exception {
        when(authentication.login(any())).thenThrow(new IllegalStateException("sensitive-value"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"me@example.com","password":"wrong"}
                """)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.message").value("An internal error occurred"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("not-json"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("MEDIA_TYPE_UNSUPPORTED"));
    }
}
