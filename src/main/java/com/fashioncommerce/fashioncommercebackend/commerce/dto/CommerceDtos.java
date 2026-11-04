package com.fashioncommerce.fashioncommercebackend.commerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CommerceDtos {
    private CommerceDtos() { }

    public record AddCartItemRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull UUID variantId,
            @Min(1) @Max(99) int quantity) { }

    public record UpdateCartItemRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @Min(1) @Max(99) int quantity,
            boolean selected) { }

    public record UpdateCartSelectionRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotEmpty @Size(max = 100) List<@NotNull UUID> itemIds,
            boolean selected) { }

    public enum CartAvailability { AVAILABLE, OUT_OF_STOCK, INSUFFICIENT_STOCK, UNAVAILABLE }

    public record CartItemResponse(
            UUID id, UUID variantId, UUID productId, String productName, String productSlug,
            String sellerSku, Map<String, String> options, String imageKey,
            long unitPriceMinor, Long compareAtPriceMinor, int quantity, boolean selected,
            int availableQuantity, CartAvailability availability) { }

    public record CartShopResponse(
            UUID shopId, String shopName, String shopSlug, long defaultShippingFeeMinor,
            List<CartItemResponse> items) { }

    public record CartResponse(
            UUID id, long version, int distinctItemCount, int totalQuantity,
            int selectedItemCount, long selectedItemsSubtotalMinor,
            List<CartShopResponse> shops, Instant updatedAt) { }

    public record CheckoutPreviewRequest(
            @NotNull @PositiveOrZero Long expectedCartVersion,
            @NotNull UUID addressId,
            @Size(max = 100) Map<@NotNull UUID, @PositiveOrZero Long> expectedUnitPrices) { }

    public record RecipientSnapshot(
            String recipientName, String recipientPhone, String addressLine1, String addressLine2,
            String ward, String district, String province, String provinceCode,
            String postalCode, String countryCode) { }

    public enum CheckoutIssueCode { PRODUCT_UNAVAILABLE, OUT_OF_STOCK, INSUFFICIENT_STOCK, PRICE_CHANGED }

    public record CheckoutIssue(
            UUID cartItemId, UUID variantId, CheckoutIssueCode code, String message,
            Long expectedUnitPriceMinor, long currentUnitPriceMinor, int requestedQuantity,
            int availableQuantity) { }

    public record CheckoutItemSnapshot(
            UUID cartItemId, UUID variantId, UUID productId, String productName,
            String sellerSku, Map<String, String> options, String imageKey,
            long unitPriceMinor, int quantity, long lineSubtotalMinor) { }

    public record CheckoutShopSnapshot(
            UUID shopId, String shopName, String shopSlug, long itemsSubtotalMinor,
            long shippingFeeMinor, long totalMinor, List<CheckoutItemSnapshot> items) { }

    public record CheckoutPreviewResponse(
            UUID snapshotId, boolean readyForOrder, long cartVersion, String currency,
            RecipientSnapshot recipient, List<CheckoutShopSnapshot> shops,
            long itemsSubtotalMinor, long shippingFeeMinor, long discountTotalMinor,
            long grandTotalMinor, List<CheckoutIssue> issues, Instant expiresAt) { }

    public record CreateAddressRequest(
            @NotBlank @Size(max = 120) String recipientName,
            @NotBlank @Size(max = 24) String recipientPhone,
            @NotBlank @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @Size(max = 120) String ward,
            @Size(max = 120) String district,
            @NotBlank @Size(max = 120) String province,
            @Size(max = 20) String provinceCode,
            @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
            boolean defaultAddress) { }

    public record UpdateAddressRequest(
            @NotBlank @Size(max = 120) String recipientName,
            @NotBlank @Size(max = 24) String recipientPhone,
            @NotBlank @Size(max = 255) String addressLine1,
            @Size(max = 255) String addressLine2,
            @Size(max = 120) String ward,
            @Size(max = 120) String district,
            @NotBlank @Size(max = 120) String province,
            @Size(max = 20) String provinceCode,
            @Size(max = 20) String postalCode,
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
            boolean defaultAddress) { }

    public record AddressResponse(
            UUID id, String recipientName, String recipientPhone, String addressLine1,
            String addressLine2, String ward, String district, String province,
            String provinceCode, String postalCode, String countryCode,
            boolean defaultAddress, Instant createdAt, Instant updatedAt) { }
}
