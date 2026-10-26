package com.fashioncommerce.fashioncommercebackend.auth.dto;

import java.time.Instant;

public record TokenResponse(String tokenType, String accessToken, long expiresIn, String refreshToken, Instant refreshExpiresAt) {
    @Override public String toString() { return "TokenResponse[REDACTED]"; }
}
