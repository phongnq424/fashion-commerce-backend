package com.fashioncommerce.fashioncommercebackend.auth.service;

import com.fashioncommerce.fashioncommercebackend.auth.dto.TokenResponse;
import com.fashioncommerce.fashioncommercebackend.security.config.SecurityProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder; this.properties = properties; this.clock = clock;
    }

    public String newRefreshToken() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    public TokenResponse issue(UUID userId, String refreshToken, Instant refreshExpiresAt) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(userId.toString()).issuer(properties.jwt().issuer())
                .issuedAt(now).expiresAt(now.plus(properties.jwt().accessTtl())).id(UUID.randomUUID().toString()).build();
        String access = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        return new TokenResponse("Bearer", access, properties.jwt().accessTtl().toSeconds(), refreshToken, refreshExpiresAt);
    }
}
