package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank @Size(max = 320) String identifier,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp,
        @StrongPassword String newPassword) {
    @Override public String toString() { return "PasswordResetRequest[REDACTED]"; }
}
