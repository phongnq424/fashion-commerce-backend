package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record RegistrationOtpRequest(
        @NotBlank @Email @Size(max = 320) String email) {
    @Override public String toString() { return "RegistrationOtpRequest[REDACTED]"; }
}
