package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetOtpRequest(
        @NotBlank @Size(max = 320) String identifier) {
    @Override public String toString() { return "PasswordResetOtpRequest[REDACTED]"; }
}
