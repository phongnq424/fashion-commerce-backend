package com.fashioncommerce.fashioncommercebackend.product.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.audit.ModerationActionRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.*;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.ProductRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProductService {
    private static final Set<String> CASE_STATUSES = Set.of("OPEN", "UNDER_REVIEW", "RESOLVED", "DISMISSED");
    private final ProductRepository products;
    private final AuditLogRepository audit;
    private final ModerationActionRepository moderation;
    private final NotificationRepository notifications;

    public ProductService(ProductRepository products, AuditLogRepository audit,
                          ModerationActionRepository moderation, NotificationRepository notifications) {
        this.products = products;
        this.audit = audit;
        this.moderation = moderation;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categories() { return products.categories(); }

    @Transactional(readOnly = true)
    public List<BrandResponse> brands() { return products.brands(); }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.create')")
    public ProductResponse create(UUID actorId, UUID shopId, CreateProductRequest request) {
        validateCatalog(request.categoryId(), request.brandId());
        validateAggregate(request);
        UUID id = products.create(actorId, shopId, request, slug(request.name()));
        audit.record(actorId, shopId, "PRODUCT_CREATED", "PRODUCT", id,
                Map.of("status", "DRAFT", "variantCount", request.variants().size()));
        return products.response(id);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.read')")
    public SliceResponse<ProductResponse> shopProducts(UUID shopId, int limit, int offset) {
        validatePage(limit, offset);
        return slice(products.shopProducts(shopId, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.read')")
    public ProductResponse shopProduct(UUID shopId, UUID productId) {
        ProductRow row = required(productId);
        if (!row.shopId().equals(shopId)) throw notFound();
        return products.response(productId);
    }

    @Transactional(readOnly = true)
    public SliceResponse<PublicProductResponse> publicProducts(UUID categoryId, int limit, int offset) {
        validatePage(limit, offset);
        return slice(products.activeProducts(categoryId, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    public PublicProductResponse publicProduct(UUID productId) {
        return products.publicProduct(productId).orElseThrow(ProductService::notFound);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse update(UUID actorId, UUID shopId, UUID productId, UpdateProductRequest request) {
        if (request.empty()) throw bad("PRODUCT_UPDATE_EMPTY", "At least one field must be supplied");
        ProductRow row = products.rowForUpdate(productId).orElseThrow(ProductService::notFound);
        requireShop(row, shopId);
        if (Set.of("HIDDEN", "REMOVED").contains(row.status())) {
            throw conflict("PRODUCT_MODERATION_LOCKED", "A moderated product cannot be edited");
        }
        if (request.categoryId() != null || request.brandId() != null) {
            validateCatalog(request.categoryId() == null ? row.categoryId() : request.categoryId(), request.brandId());
        }
        products.update(productId, request);
        audit.record(actorId, row.shopId(), "PRODUCT_UPDATED", "PRODUCT", productId, Map.of());
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.publish')")
    public ProductResponse publish(UUID actorId, UUID shopId, UUID productId) {
        ProductRow row = products.rowForUpdate(productId).orElseThrow(ProductService::notFound);
        requireShop(row, shopId);
        if (!products.readyToPublish(productId) || !products.publish(productId)) {
            throw conflict("PRODUCT_NOT_PUBLISHABLE",
                    "A draft product needs an active SKU, inventory record and primary image");
        }
        audit.record(actorId, shopId, "PRODUCT_PUBLISHED", "PRODUCT", productId,
                Map.of("from", row.status(), "to", "ACTIVE"));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.publish')")
    public ProductResponse unpublish(UUID actorId, UUID shopId, UUID productId) {
        ProductRow row = products.rowForUpdate(productId).orElseThrow(ProductService::notFound);
        requireShop(row, shopId);
        if (!products.unpublish(productId)) {
            throw conflict("PRODUCT_STATE_CONFLICT", "Only an active product can be unpublished");
        }
        audit.record(actorId, shopId, "PRODUCT_UNPUBLISHED", "PRODUCT", productId,
                Map.of("from", row.status(), "to", "ARCHIVED"));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.inventory.adjust')")
    public ProductResponse setInventory(UUID actorId, UUID shopId, UUID variantId, InventoryRequest request) {
        var before = products.inventoryForUpdate(variantId, shopId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "PRODUCT_VARIANT_NOT_FOUND", "Product variant not found"));
        if (request.quantityOnHand() < before.reserved()) {
            throw conflict("INVENTORY_BELOW_RESERVED", "On-hand quantity cannot be lower than reserved quantity");
        }
        products.setInventory(actorId, shopId, before, request);
        audit.record(actorId, shopId, "INVENTORY_ADJUSTED", "PRODUCT_VARIANT", variantId,
                Map.of("from", before.onHand(), "to", request.quantityOnHand()));
        return products.response(before.productId());
    }

    @Transactional
    public ModerationCaseResponse report(UUID reporterId, UUID productId, ReportProductRequest request) {
        ProductRow product = required(productId);
        if (product.ownerUserId().equals(reporterId)) {
            throw conflict("PRODUCT_SELF_REPORT_NOT_ALLOWED", "A shop owner cannot report their own product");
        }
        if (!"ACTIVE".equals(product.status())) {
            throw conflict("PRODUCT_NOT_REPORTABLE", "Only an active product can be reported");
        }
        UUID caseId = products.createCase(reporterId, productId, request);
        audit.record(reporterId, product.shopId(), "PRODUCT_REPORTED", "PRODUCT_MODERATION_CASE", caseId,
                Map.of("productId", productId, "reason", request.reason().name()));
        return products.moderationCase(caseId).orElseThrow();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'product.moderate')")
    public SliceResponse<ModerationCaseResponse> cases(String status, int limit, int offset) {
        validatePage(limit, offset);
        String normalized = status == null || status.isBlank() ? "OPEN" : status.strip().toUpperCase(Locale.ROOT);
        if (!CASE_STATUSES.contains(normalized)) throw bad("MODERATION_STATUS_INVALID", "Invalid moderation case status");
        return slice(products.moderationCases(normalized, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'product.moderate')")
    public ModerationCaseResponse moderationCase(UUID caseId) {
        return products.moderationCase(caseId).orElseThrow(ProductService::caseNotFound);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'product.moderate')")
    public ProductResponse moderate(UUID moderatorId, UUID productId, ModerateProductRequest request) {
        ProductRow before = products.rowForUpdate(productId).orElseThrow(ProductService::notFound);
        if (request.action() == ModerationAction.DISMISS && request.caseId() == null) {
            throw bad("MODERATION_CASE_REQUIRED", "Dismissing a report requires a moderation case");
        }
        ModerationCaseResponse moderationCase = null;
        if (request.caseId() != null) {
            moderationCase = products.moderationCaseForUpdate(request.caseId()).orElseThrow(ProductService::caseNotFound);
            if (!moderationCase.productId().equals(productId)
                    || !Set.of("OPEN", "UNDER_REVIEW").contains(moderationCase.status())) throw caseNotFound();
        }
        if (!products.moderateProduct(productId, request.action())) {
            throw conflict("PRODUCT_MODERATION_STATE_CONFLICT", "The action is invalid for the current product status");
        }
        if (moderationCase != null && !products.resolveCase(moderationCase.id(), moderatorId,
                request.action().name(), request.reason().strip())) {
            throw conflict("PRODUCT_MODERATION_CASE_CONFLICT", "The moderation case is already resolved");
        }
        ProductRow after = required(productId);
        moderation.productDecision(moderatorId, productId, request.action().name(),
                request.reason().strip(), before.status(), after.status());
        audit.record(moderatorId, before.shopId(), "PRODUCT_MODERATION_" + request.action().name(),
                "PRODUCT", productId, Map.of("from", before.status(), "to", after.status(),
                        "reason", request.reason().strip()));
        notifications.create(before.ownerUserId(), "PRODUCT_MODERATION_" + request.action().name(),
                "Product moderation update", "A moderation action was applied to " + before.name() + ".",
                Map.of("productId", productId, "action", request.action().name(),
                        "reason", request.reason().strip()));
        return products.response(productId);
    }

    private void validateCatalog(UUID categoryId, UUID brandId) {
        if (!products.activeCategory(categoryId)) throw bad("PRODUCT_CATEGORY_INVALID", "Category is unavailable");
        if (brandId != null && !products.activeBrand(brandId)) throw bad("PRODUCT_BRAND_INVALID", "Brand is unavailable");
    }

    private void validateAggregate(CreateProductRequest request) {
        Set<String> optionTypes = new LinkedHashSet<>();
        Map<String, Set<String>> values = new HashMap<>();
        for (OptionRequest option : request.options()) {
            String type = option.type().name();
            if (!optionTypes.add(type)) throw bad("PRODUCT_OPTION_DUPLICATED", "Each option type may appear once");
            Set<String> codes = option.values().stream().map(value -> normalize(value.code()))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (codes.size() != option.values().size()) throw bad("PRODUCT_OPTION_VALUE_DUPLICATED", "Option value codes must be unique");
            values.put(type, codes);
        }
        Set<String> skus = new HashSet<>();
        Set<String> signatures = new HashSet<>();
        for (VariantRequest variant : request.variants()) {
            if (!skus.add(normalize(variant.sellerSku()))) throw bad("PRODUCT_SKU_DUPLICATED", "SKU values must be unique");
            Map<String, String> selections = variant.selections().entrySet().stream().collect(Collectors.toMap(
                    entry -> normalize(entry.getKey()), entry -> normalize(entry.getValue()), (a, b) -> a));
            if (!selections.keySet().equals(optionTypes)) {
                throw bad("PRODUCT_VARIANT_OPTIONS_INVALID", "Every SKU must select one value for every product option");
            }
            for (Map.Entry<String, String> selection : selections.entrySet()) {
                if (!values.getOrDefault(selection.getKey(), Set.of()).contains(selection.getValue())) {
                    throw bad("PRODUCT_VARIANT_OPTIONS_INVALID", "SKU references an unknown option value");
                }
            }
            if (!signatures.add(ProductRepository.signature(selections))) {
                throw bad("PRODUCT_VARIANT_DUPLICATED", "Two SKUs cannot have the same option combination");
            }
            if (variant.compareAtPriceMinor() != null && variant.compareAtPriceMinor() < variant.priceMinor()) {
                throw bad("PRODUCT_COMPARE_PRICE_INVALID", "Compare-at price must not be lower than price");
            }
        }
        long primary = request.images().stream().filter(image -> image.primary() && image.variantSellerSku() == null).count();
        if (primary != 1) throw bad("PRODUCT_PRIMARY_IMAGE_REQUIRED", "Exactly one product-level primary image is required");
        Set<String> variantPrimary = new HashSet<>();
        for (ImageRequest image : request.images()) {
            if (image.variantSellerSku() != null && !skus.contains(normalize(image.variantSellerSku()))) {
                throw bad("PRODUCT_IMAGE_VARIANT_INVALID", "Image references an unknown SKU");
            }
            if (image.primary() && image.variantSellerSku() != null
                    && !variantPrimary.add(normalize(image.variantSellerSku()))) {
                throw bad("PRODUCT_IMAGE_PRIMARY_DUPLICATED", "A SKU may have only one primary image");
            }
        }
    }

    private ProductRow required(UUID id) { return products.row(id).orElseThrow(ProductService::notFound); }
    private static void requireShop(ProductRow row, UUID shopId) { if (!row.shopId().equals(shopId)) throw notFound(); }
    private static String normalize(String value) { return value.strip().toUpperCase(Locale.ROOT); }
    private static String slug(String name) {
        String base = Normalizer.normalize(name.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd').replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "product";
        if (base.length() > 230) base = base.substring(0, 230).replaceAll("-$", "");
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw bad("PAGINATION_INVALID", "Limit must be 1-100 and offset non-negative");
    }
    private static <T> SliceResponse<T> slice(List<T> rows, int limit, int offset) {
        boolean more = rows.size() > limit;
        return new SliceResponse<>(more ? rows.subList(0, limit) : rows, limit, offset, more);
    }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found"); }
    private static ApiException caseNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "MODERATION_CASE_NOT_FOUND", "Moderation case not found"); }
}
