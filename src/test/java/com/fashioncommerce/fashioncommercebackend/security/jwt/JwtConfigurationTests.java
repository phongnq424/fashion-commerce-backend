package com.fashioncommerce.fashioncommercebackend.security.jwt;

import com.fashioncommerce.fashioncommercebackend.security.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JwtConfigurationTests {
    private final JwtConfiguration config = new JwtConfiguration();
    private final Instant now = Instant.parse("2026-10-21T07:00:00Z");
    private SecurityProperties properties;
    private JwtEncoder encoder;
    private JwtDecoder decoder;

    @BeforeEach void setup() {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        properties = new SecurityProperties(new SecurityProperties.Jwt("test-issuer", Base64.getEncoder().encodeToString(key), Duration.ofMinutes(15)), Duration.ofDays(14), List.of());
        encoder = config.jwtEncoder(config.jwtKey(properties));
        decoder = config.jwtDecoder(config.jwtKey(properties), properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().subject(UUID.randomUUID().toString()).id(UUID.randomUUID().toString())
                .issuer("test-issuer").issuedAt(now.minusSeconds(5)).expiresAt(now.plusSeconds(900));
    }
    private String sign(JwtClaimsSet.Builder claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
    }

    @Test void acceptsSignedTokenWithRequiredClaims() { assertThat(decoder.decode(sign(claims())).getClaimAsString("iss")).isEqualTo("test-issuer"); }
    @Test void rejectsExpiredToken() { assertThatThrownBy(() -> decoder.decode(sign(claims().issuedAt(now.minusSeconds(100)).expiresAt(now.minusSeconds(1))))).isInstanceOf(JwtException.class); }
    @Test void rejectsWrongIssuer() { assertThatThrownBy(() -> decoder.decode(sign(claims().issuer("other")))).isInstanceOf(JwtException.class); }
    @Test void rejectsMissingExpiration() { assertThatThrownBy(() -> decoder.decode(sign(claims().claims(map -> map.remove("exp"))))).isInstanceOf(JwtException.class); }
    @Test void rejectsMissingIssuedAt() { assertThatThrownBy(() -> decoder.decode(sign(claims().claims(map -> map.remove("iat"))))).isInstanceOf(JwtException.class); }
    @Test void rejectsMissingJti() { assertThatThrownBy(() -> decoder.decode(sign(claims().claims(map -> map.remove("jti"))))).isInstanceOf(JwtException.class); }
    @Test void rejectsInvalidSubject() { assertThatThrownBy(() -> decoder.decode(sign(claims().subject("email@example.com")))).isInstanceOf(JwtException.class); }
    @Test void rejectsFutureIssuedAt() { assertThatThrownBy(() -> decoder.decode(sign(claims().issuedAt(now.plusSeconds(60))))).isInstanceOf(JwtException.class); }
    @Test void rejectsWrongSignatureAndMalformedToken() {
        String signed = sign(claims());
        setup();
        assertThatThrownBy(() -> decoder.decode(signed)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("invalid")).isInstanceOf(JwtException.class);
    }
    @Test void rejectsWeakKey() {
        var weak = new SecurityProperties(new SecurityProperties.Jwt("test", Base64.getEncoder().encodeToString(new byte[16]), Duration.ofMinutes(15)), Duration.ofDays(14), List.of());
        assertThatThrownBy(() -> config.jwtKey(weak)).isInstanceOf(IllegalStateException.class);
        assertThat(properties.toString()).doesNotContain(properties.jwt().secretBase64());
    }
    @Test void issuedTokenContainsOnlyRequiredClaimsAndRefreshIsOpaque() {
        var service = new com.fashioncommerce.fashioncommercebackend.auth.service.TokenService(encoder, properties, Clock.fixed(now, ZoneOffset.UTC));
        UUID userId = UUID.randomUUID();
        String refresh = service.newRefreshToken();
        assertThat(refresh).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(service.newRefreshToken());
        assertThat(service.hash(refresh)).matches("[0-9a-f]{64}").isNotEqualTo(refresh);
        var response = service.issue(userId, refresh, now.plus(Duration.ofDays(14)));
        var decoded = decoder.decode(response.accessToken());
        assertThat(decoded.getClaims().keySet()).containsExactlyInAnyOrder("sub", "iss", "iat", "exp", "jti");
        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getExpiresAt()).isEqualTo(now.plusSeconds(900));
        assertThat(response.toString()).doesNotContain(response.accessToken(), refresh);
    }
}
