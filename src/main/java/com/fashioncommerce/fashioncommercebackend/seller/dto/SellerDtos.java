package com.fashioncommerce.fashioncommercebackend.seller.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public final class SellerDtos {
    private SellerDtos() { }

    public record ApplyRequest(
            @NotBlank @Size(max = 160) String proposedShopName,
            @NotBlank @Email @Size(max = 320) String contactEmail,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String contactPhone,
            @NotBlank @Size(max = 180) String legalName,
            @Pattern(regexp = "[0-9]{4}") String legalIdentifierLast4,
            @Size(max = 500) String documentKey) {
        @Override public String toString() { return "ApplyRequest[REDACTED]"; }
    }

    public enum ReviewDecision { APPROVED, REJECTED }

    public record ReviewRequest(
            @NotNull ReviewDecision decision,
            @Size(max = 2000) String note) { }

    public record ApplicationResponse(
            UUID id,
            UUID applicantUserId,
            String proposedShopName,
            String contactEmail,
            String contactPhone,
            String legalName,
            String legalIdentifierLast4,
            String documentKey,
            String status,
            UUID reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            Instant createdAt,
            Instant updatedAt) { }
}

