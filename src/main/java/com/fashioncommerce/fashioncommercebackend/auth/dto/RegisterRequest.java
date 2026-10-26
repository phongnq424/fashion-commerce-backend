package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.constraints.*;

public record RegisterRequest(@NotBlank @Email @Size(max = 320) String email,
                              @StrongPassword String password,
                              @NotBlank @Size(max = 120) String displayName,
                              @Pattern(regexp = "\\+?[0-9]{8,15}") String phone) {
    @Override public String toString() { return "RegisterRequest[REDACTED]"; }
}
