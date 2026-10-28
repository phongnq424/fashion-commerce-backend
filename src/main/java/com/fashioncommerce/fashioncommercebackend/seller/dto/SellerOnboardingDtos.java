package com.fashioncommerce.fashioncommercebackend.seller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SellerOnboardingDtos {
    private SellerOnboardingDtos() { }

    public record ShopProfileRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String phone,
            @Size(max = 500) String avatarKey,
            @Size(max = 5000) String description,
            @Size(max = 500) String coverKey) { }

    public record ShopProfileResponse(
            UUID shopId, String name, String slug, String email, String phone,
            String avatarKey, String description, String coverKey, String status) { }

    public record PickupAddressRequest(
            @NotBlank @Size(max = 120) String contactName,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String contactPhone,
            @Size(max = 20) String provinceCode,
            @NotBlank @Size(max = 120) String provinceName,
            @Size(max = 20) String wardCode,
            @NotBlank @Size(max = 120) String wardName,
            @NotBlank @Size(max = 500) String addressDetail,
            @NotNull Boolean defaultPickup) { }

    public record PickupAddressResponse(
            UUID id, String contactName, String contactPhone, String provinceCode,
            String provinceName, String wardCode, String wardName, String addressDetail,
            boolean defaultPickup, boolean active) { }

    public record ShippingSelection(
            @NotBlank @Size(max = 60) String providerCode,
            @NotNull Boolean enabled) { }

    public record ShippingConfigurationRequest(
            @NotNull UUID pickupAddressId,
            @NotEmpty @Size(max = 20) List<@Valid ShippingSelection> providers) { }

    public record ShippingProviderResponse(
            String code, String name, String description, boolean available,
            boolean enabled, UUID pickupAddressId) { }

    public enum BusinessType { INDIVIDUAL, HOUSEHOLD_BUSINESS, ENTERPRISE }

    public record RegisteredAddress(
            @Size(max = 20) String provinceCode,
            @NotBlank @Size(max = 120) String provinceName,
            @Size(max = 20) String wardCode,
            @NotBlank @Size(max = 120) String wardName,
            @NotBlank @Size(max = 500) String addressDetail) { }

    public record TaxProfileRequest(
            @NotNull BusinessType businessType,
            @Size(max = 180) String legalName,
            @Size(max = 200) String businessName,
            @Size(max = 180) String legalRepresentativeName,
            @NotBlank @Pattern(regexp = "[0-9A-Za-z-]{8,32}") String taxCode,
            @Size(max = 80) String businessRegistrationNumber,
            @PastOrPresent LocalDate issuedDate,
            @Size(max = 255) String issuedBy,
            @NotNull @Valid RegisteredAddress registeredAddress,
            @NotBlank @Email @Size(max = 320) String invoiceEmail,
            @Size(max = 500) String registrationDocumentKey) { }

    public record TaxProfileResponse(
            BusinessType businessType, String legalName, String businessName,
            String legalRepresentativeName, String taxCode, String businessRegistrationNumber,
            LocalDate issuedDate, String issuedBy, Map<String, Object> registeredAddress,
            String invoiceEmail, String registrationDocumentKey, String status,
            UUID reviewedBy, Instant reviewedAt, String reviewNote) { }

    public record IdentityVerificationRequest(
            @NotBlank @Size(max = 180) String legalName,
            @NotBlank @Pattern(regexp = "[0-9]{4}") String documentNumberLast4,
            @NotBlank @Size(max = 500) String frontDocumentKey,
            @NotBlank @Size(max = 500) String backDocumentKey,
            @NotBlank @Size(max = 500) String selfieDocumentKey) { }

    public record IdentityVerificationResponse(
            String documentType, String legalName, String documentNumberLast4,
            String frontDocumentKey, String backDocumentKey, String selfieDocumentKey,
            String status, UUID reviewedBy, Instant reviewedAt, String reviewNote) { }

    public record PolicyAcceptanceRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,80}") String policyVersion,
            @AssertTrue(message = "accepted must be true") boolean accepted) { }

    public record PolicyVersionResponse(
            String version, String title, boolean required, Instant effectiveAt, boolean accepted) { }

    public enum ReviewDecision { APPROVED, REJECTED }

    public record ReviewRequest(
            @NotNull ReviewDecision decision,
            @Size(max = 2000) String note) { }

    public record OnboardingResponse(
            UUID id, UUID applicantUserId, String status,
            ShopProfileResponse shop, List<PickupAddressResponse> pickupAddresses,
            List<ShippingProviderResponse> shippingProviders,
            TaxProfileResponse taxProfile, IdentityVerificationResponse identityVerification,
            List<String> acceptedPolicyVersions, Instant submittedAt,
            UUID reviewedBy, Instant reviewedAt, String reviewNote, Instant completedAt,
            Instant createdAt, Instant updatedAt) { }
}
