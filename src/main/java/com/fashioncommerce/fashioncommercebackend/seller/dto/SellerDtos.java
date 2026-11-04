package com.fashioncommerce.fashioncommercebackend.seller.dto;

import java.time.Instant;
import java.util.UUID;

public final class SellerDtos {
    private SellerDtos() { }

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

    public record ApplicationSummaryResponse(
            UUID id, UUID applicantUserId, String proposedShopName, String contactEmail,
            String status, Instant reviewedAt, Instant createdAt, Instant updatedAt) { }
}

