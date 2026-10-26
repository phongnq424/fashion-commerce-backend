package com.fashioncommerce.fashioncommercebackend.security.principal;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;
import java.util.UUID;

public final class CurrentIdentity {
    private CurrentIdentity() { }

    public static Optional<UUID> userId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(token.getToken().getSubject()));
        }
        catch (IllegalArgumentException | NullPointerException ex) { return Optional.empty(); }
    }
}
