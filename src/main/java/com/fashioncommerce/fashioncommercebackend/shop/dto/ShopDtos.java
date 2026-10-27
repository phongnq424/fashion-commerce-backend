package com.fashioncommerce.fashioncommercebackend.shop.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ShopDtos {
    private ShopDtos() { }

    public record PickupAddress(
            @NotBlank @Size(max = 120) String contactName,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String contactPhone,
            @NotBlank @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @Size(max = 120) String ward,
            @Size(max = 120) String district,
            @NotBlank @Size(max = 120) String province,
            @Size(max = 20) String provinceCode,
            @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode) {

        public Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("contactName", contactName.strip());
            value.put("contactPhone", contactPhone.strip());
            value.put("addressLine1", addressLine1.strip());
            value.put("addressLine2", trimToNull(addressLine2));
            value.put("ward", trimToNull(ward));
            value.put("district", trimToNull(district));
            value.put("province", province.strip());
            value.put("provinceCode", trimToNull(provinceCode));
            value.put("postalCode", trimToNull(postalCode));
            value.put("countryCode", countryCode);
            return value;
        }
    }

    public record CreateRequest(
            @NotNull UUID applicationId,
            @NotBlank @Size(max = 160) String name,
            @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 180) String slug,
            @Size(max = 5000) String description,
            @Size(max = 500) String logoKey,
            @Size(max = 500) String bannerKey,
            @NotBlank @Email @Size(max = 320) String contactEmail,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{8,15}") String contactPhone,
            @NotNull @Valid PickupAddress pickupAddress) { }

    public record UpdateRequest(
            @Size(min = 1, max = 160) String name,
            @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 180) String slug,
            @Size(max = 5000) String description,
            @Size(max = 500) String logoKey,
            @Size(max = 500) String bannerKey,
            @Email @Size(max = 320) String contactEmail,
            @Pattern(regexp = "\\+?[0-9]{8,15}") String contactPhone,
            @Valid PickupAddress pickupAddress) {
        public boolean isEmpty() {
            return name == null && slug == null && description == null && logoKey == null && bannerKey == null
                    && contactEmail == null && contactPhone == null && pickupAddress == null;
        }
    }

    public record ShopResponse(
            UUID id,
            UUID ownerUserId,
            UUID applicationId,
            String name,
            String slug,
            String description,
            String logoKey,
            String bannerKey,
            String contactEmail,
            String contactPhone,
            Map<String, Object> pickupAddress,
            String status,
            Instant activatedAt,
            Instant suspendedAt,
            Instant createdAt,
            Instant updatedAt) { }

    public record SettingsResponse(
            UUID shopId,
            short handlingDays,
            short returnWindowDays,
            long defaultShippingFeeMinor,
            boolean allowCod,
            boolean autoAcceptOrders) { }

    public record UpdateSettingsRequest(
            @NotNull @Min(0) @Max(30) Short handlingDays,
            @NotNull @Min(0) @Max(90) Short returnWindowDays,
            @NotNull @PositiveOrZero Long defaultShippingFeeMinor,
            @NotNull Boolean allowCod,
            @NotNull Boolean autoAcceptOrders) { }

    public record InviteMemberRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotEmpty @Size(max = 6) List<@NotBlank @Size(max = 80) String> roles) { }

    public record UpdateMemberRolesRequest(
            @NotEmpty @Size(max = 6) List<@NotBlank @Size(max = 80) String> roles) { }

    public record MemberResponse(
            UUID memberId,
            UUID userId,
            String email,
            String displayName,
            String status,
            UUID invitedBy,
            Instant joinedAt,
            Instant createdAt,
            List<String> roles) { }

    public record RoleResponse(String code, String name, String description) { }

    public record InvitationResponse(
            UUID memberId,
            UUID shopId,
            String shopName,
            UUID invitedBy,
            Instant createdAt,
            List<String> roles) { }

    public record TransferOwnershipRequest(
            @NotNull UUID newOwnerUserId,
            boolean keepPreviousOwnerAsManager) { }

    public record OwnershipTransferResponse(
            UUID id,
            UUID shopId,
            String shopName,
            UUID fromOwnerUserId,
            UUID toUserId,
            boolean keepPreviousOwnerAsManager,
            String status,
            Instant expiresAt,
            Instant decidedAt,
            Instant createdAt) { }

    public enum ModerationAction { SUSPEND, REACTIVATE, CLOSE }

    public record ModerateShopRequest(
            @NotNull ModerationAction action,
            @NotBlank @Size(max = 2000) String reason) { }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
