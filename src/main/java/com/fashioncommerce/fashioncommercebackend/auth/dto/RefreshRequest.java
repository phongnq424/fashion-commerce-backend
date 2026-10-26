package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.constraints.*;

public record RefreshRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String refreshToken) {
    @Override public String toString() { return "RefreshRequest[REDACTED]"; }
}
