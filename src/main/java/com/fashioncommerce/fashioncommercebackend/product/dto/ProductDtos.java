package com.fashioncommerce.fashioncommercebackend.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProductDtos {
    private ProductDtos() { }

    public enum Gender { MALE, FEMALE, UNISEX, KIDS }
    public enum OptionType { SIZE, COLOR }

    public record OptionValueRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,60}") String code,
            @NotBlank @Size(max = 100) String label,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String hexColor,
            @Min(0) @Max(1000) int sortOrder) { }

    public record OptionRequest(
            @NotNull OptionType type,
            @NotBlank @Size(max = 80) String displayName,
            @Min(0) @Max(1000) int sortOrder,
            @NotEmpty @Size(max = 100) List<@Valid OptionValueRequest> values) { }

    public record VariantRequest(
            @NotBlank @Size(max = 100) String sellerSku,
            @Size(max = 100) String barcode,
            @NotNull Map<@NotBlank String, @NotBlank String> selections,
            @PositiveOrZero @Max(1_000_000_000_000L) long priceMinor,
            @PositiveOrZero Long compareAtPriceMinor,
            @Min(1) @Max(1_000_000) int weightGrams,
            @Positive Integer lengthMm,
            @Positive Integer widthMm,
            @Positive Integer heightMm,
            @Min(0) int initialStock,
            @Min(0) int reorderPoint) { }

    public record ImageRequest(
            @NotBlank @Size(max = 500) String storageKey,
            @Size(max = 250) String altText,
            @Min(0) int sortOrder,
            boolean primary,
            @Size(max = 100) String variantSellerSku) { }

    public record CreateProductRequest(
            @NotNull UUID categoryId,
            UUID brandId,
            @Size(max = 80) String sellerProductCode,
            @NotBlank @Size(max = 240) String name,
            @NotBlank @Size(max = 20_000) String description,
            @Size(max = 160) String material,
            @Size(max = 80) String fitType,
            @NotNull Gender targetGender,
            @Size(max = 5000) String careInstructions,
            Map<String, Object> sizeChart,
            @NotNull @Size(max = 2) List<@Valid OptionRequest> options,
            @NotEmpty @Size(max = 500) List<@Valid VariantRequest> variants,
            @NotEmpty @Size(max = 30) List<@Valid ImageRequest> images) { }

    public record UpdateProductRequest(
            UUID categoryId,
            UUID brandId,
            @Size(max = 80) String sellerProductCode,
            @Size(min = 1, max = 240) String name,
            @Size(min = 1, max = 20_000) String description,
            @Size(max = 160) String material,
            @Size(max = 80) String fitType,
            Gender targetGender,
            @Size(max = 5000) String careInstructions,
            Map<String, Object> sizeChart) {
        public boolean empty() {
            return categoryId == null && brandId == null && sellerProductCode == null && name == null
                    && description == null && material == null && fitType == null && targetGender == null
                    && careInstructions == null && sizeChart == null;
        }
    }

    public record InventoryRequest(
            @Min(0) int quantityOnHand,
            @Min(0) int reorderPoint,
            @Size(max = 500) String note) { }

    public record OptionValueResponse(UUID id, String code, String label, String hexColor, int sortOrder) { }
    public record OptionResponse(UUID id, String type, String displayName, int sortOrder,
                                 List<OptionValueResponse> values) { }
    public record VariantResponse(UUID id, String sellerSku, String barcode, Map<String, String> selections,
                                  long priceMinor, Long compareAtPriceMinor, int weightGrams,
                                  Integer lengthMm, Integer widthMm, Integer heightMm, String status,
                                  int quantityOnHand, int quantityReserved, int availableQuantity, int reorderPoint) { }
    public record ImageResponse(UUID id, UUID variantId, String storageKey, String altText,
                                int sortOrder, boolean primary) { }

    public record ProductResponse(
            UUID id, UUID shopId, UUID categoryId, UUID brandId, String sellerProductCode,
            String name, String slug, String description, String material, String fitType,
            String targetGender, String careInstructions, Map<String, Object> sizeChart,
            String status, Instant publishedAt, List<OptionResponse> options,
            List<VariantResponse> variants, List<ImageResponse> images,
            Instant createdAt, Instant updatedAt) { }

    public record PublicVariantResponse(
            UUID id, String sellerSku, Map<String, String> selections,
            long priceMinor, Long compareAtPriceMinor, int availableQuantity) { }

    public record PublicProductResponse(
            UUID id, UUID shopId, UUID categoryId, UUID brandId, String name, String slug,
            String description, String material, String fitType, String targetGender,
            String careInstructions, Map<String, Object> sizeChart, Instant publishedAt,
            List<OptionResponse> options, List<PublicVariantResponse> variants,
            List<ImageResponse> images) { }

    public record CategoryResponse(UUID id, UUID parentId, String name, String slug, String description) { }
    public record BrandResponse(UUID id, String name, String slug, String logoKey) { }

    public enum ReportReason { COUNTERFEIT, PROHIBITED, MISLEADING, COPYRIGHT, INAPPROPRIATE, OTHER }
    public record ReportProductRequest(
            @NotNull ReportReason reason,
            @Size(max = 2000) String description,
            @Size(max = 10) List<@NotBlank @Size(max = 500) String> evidenceKeys) { }

    public record ModerationCaseResponse(
            UUID id, UUID productId, String productName, UUID shopId, UUID reporterUserId,
            String reasonCode, String description, List<String> evidenceKeys, String status,
            UUID assignedTo, String resolutionAction, String resolutionNote,
            Instant resolvedAt, Instant createdAt, Instant updatedAt) { }

    public enum ModerationAction { WARN, HIDE, REMOVE, RESTORE, DISMISS }
    public record ModerateProductRequest(
            UUID caseId,
            @NotNull ModerationAction action,
            @NotBlank @Size(max = 2000) String reason) { }
}
