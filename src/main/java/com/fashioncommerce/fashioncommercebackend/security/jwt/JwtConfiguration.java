package com.fashioncommerce.fashioncommercebackend.security.jwt;

import com.fashioncommerce.fashioncommercebackend.security.config.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean
    SecretKey jwtKey(SecurityProperties properties) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(properties.jwt().secretBase64()); }
        catch (IllegalArgumentException ex) { throw new IllegalStateException("JWT_SECRET_BASE64 must be valid Base64"); }
        if (bytes.length < 32) throw new IllegalStateException("JWT key must contain at least 32 random bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) {
        return NimbusJwtEncoder.withSecretKey(jwtKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey, SecurityProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(properties.jwt().issuer()), jwt -> {
                    try {
                        UUID.fromString(jwt.getSubject());
                        UUID.fromString(jwt.getId());
                        Instant issued = jwt.getIssuedAt();
                        Instant expires = jwt.getExpiresAt();
                        if (issued != null && expires != null && expires.isAfter(issued)
                                && !issued.isAfter(clock.instant().plusSeconds(30))) {
                            return OAuth2TokenValidatorResult.success();
                        }
                    } catch (IllegalArgumentException | NullPointerException ignored) { }
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid required claims", null));
                }));
        return decoder;
    }
}
