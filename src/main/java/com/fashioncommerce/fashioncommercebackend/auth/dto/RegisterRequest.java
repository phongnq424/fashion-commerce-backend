package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(@NotBlank @Email @Size(max = 320) String email,
                              @NotBlank @Pattern(regexp = "^(?:\\+84|84|0)[35789][0-9]{8}$") String phone,
                              @StrongPassword String password,
                              @NotBlank @Size(max = 120) String displayName,
                              @NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp) {
    @Override public String toString() { return "RegisterRequest[REDACTED]"; }
}
