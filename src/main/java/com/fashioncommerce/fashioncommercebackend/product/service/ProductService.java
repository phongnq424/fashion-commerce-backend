package com.fashioncommerce.fashioncommercebackend.product.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.audit.ModerationActionRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.common.response.CursorPageResponse;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.*;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.CatalogCursor;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.CatalogEntry;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.CatalogFilter;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.ImageRow;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.OptionSelection;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.ProductRow;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.VariantRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    public SliceResponse<ShopProductSummaryResponse> shopProducts(UUID shopId, int limit, int offset) {
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
    public CursorPageResponse<PublicProductSummaryResponse> publicProducts(PublicProductQuery query) {
        validateCatalogQuery(query);
        ProductSort sort = query.sort() == null ? ProductSort.NEWEST : query.sort();
        CatalogCursor cursor = decodeCursor(query.cursor(), sort);
        CatalogFilter filter = new CatalogFilter(trimToNull(query.query()), query.categoryId(), query.brandId(),
                query.gender(), query.minPriceMinor(), query.maxPriceMinor(), query.inStock(), sort);
        List<CatalogEntry> rows = products.activeProducts(filter, cursor, query.limit() + 1);
        boolean more = rows.size() > query.limit();
        List<CatalogEntry> page = more ? rows.subList(0, query.limit()) : rows;
        String next = more && !page.isEmpty() ? encodeCursor(sort, page.get(page.size() - 1)) : null;
        return new CursorPageResponse<>(page.stream().map(CatalogEntry::product).toList(), query.limit(), next, more);
    }

    @Transactional(readOnly = true)
    public PublicProductResponse publicProduct(UUID productId) {
        return products.publicProduct(productId).orElseThrow(ProductService::notFound);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse update(UUID actorId, UUID shopId, UUID productId, UpdateProductRequest request) {
        if (request.empty()) throw bad("PRODUCT_UPDATE_EMPTY", "At least one field must be supplied");
        ProductRow row = editable(shopId, productId, request.expectedVersion());
        if (request.categoryId() != null || request.brandId() != null) {
            validateCatalog(request.categoryId() == null ? row.categoryId() : request.categoryId(), request.brandId());
        }
        if (!products.update(productId, request)) throw versionConflict();
        audit.record(actorId, row.shopId(), "PRODUCT_UPDATED", "PRODUCT", productId, Map.of());
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.publish')")
    public ProductResponse publish(UUID actorId, UUID shopId, UUID productId, long expectedVersion) {
        ProductRow row = products.rowForUpdate(productId).orElseThrow(ProductService::notFound);
        requireShop(row, shopId);
        requireVersion(row, expectedVersion);
        if (!products.readyToPublish(productId) || !products.publish(productId, expectedVersion)) {
            throw conflict("PRODUCT_NOT_PUBLISHABLE",
                    "A draft product needs an active SKU, inventory record and primary image");
        }
        audit.record(actorId, shopId, "PRODUCT_PUBLISHED", "PRODUCT", productId,
                Map.of("from", row.status(), "to", "ACTIVE"));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.publish')")
    public ProductResponse unpublish(UUID actorId, UUID shopId, UUID productId, long expectedVersion) {
        ProductRow row = products.rowForUpdate(productId).orElseThrow(ProductService::notFound);
        requireShop(row, shopId);
        requireVersion(row, expectedVersion);
        if (!products.unpublish(productId, expectedVersion)) {
            throw conflict("PRODUCT_STATE_CONFLICT", "Only an active product can be unpublished");
        }
        audit.record(actorId, shopId, "PRODUCT_UNPUBLISHED", "PRODUCT", productId,
                Map.of("from", row.status(), "to", "ARCHIVED"));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse createVariant(UUID actorId, UUID shopId, UUID productId, CreateVariantRequest request) {
        ProductRow product = editable(shopId, productId, request.expectedVersion());
        if (request.compareAtPriceMinor() != null && request.compareAtPriceMinor() < request.priceMinor()) {
            throw bad("PRODUCT_COMPARE_PRICE_INVALID", "Compare-at price must not be lower than price");
        }
        Map<String, OptionSelection> selections = selectedOptions(productId, request.selections());
        UUID variantId = products.createVariant(actorId, shopId, productId, request, selections);
        advanceVersion(productId, request.expectedVersion());
        audit.record(actorId, shopId, "PRODUCT_VARIANT_CREATED", "PRODUCT_VARIANT", variantId,
                Map.of("productId", productId, "sku", normalize(request.sellerSku())));
        return products.response(product.id());
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse updateVariant(UUID actorId, UUID shopId, UUID productId, UUID variantId,
                                         UpdateVariantRequest request) {
        if (request.empty()) throw bad("PRODUCT_VARIANT_UPDATE_EMPTY", "At least one field must be supplied");
        editable(shopId, productId, request.expectedVersion());
        requiredVariant(productId, variantId);
        products.updateVariant(variantId, request);
        advanceVersion(productId, request.expectedVersion());
        audit.record(actorId, shopId, "PRODUCT_VARIANT_UPDATED", "PRODUCT_VARIANT", variantId, Map.of());
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse updateVariantPrice(UUID actorId, UUID shopId, UUID productId, UUID variantId,
                                              UpdateVariantPriceRequest request) {
        editable(shopId, productId, request.expectedVersion());
        VariantRow variant = requiredVariant(productId, variantId);
        if (request.compareAtPriceMinor() != null && request.compareAtPriceMinor() < request.priceMinor()) {
            throw bad("PRODUCT_COMPARE_PRICE_INVALID", "Compare-at price must not be lower than price");
        }
        products.updateVariantPrice(variantId, request);
        advanceVersion(productId, request.expectedVersion());
        audit.record(actorId, shopId, "PRODUCT_VARIANT_PRICE_UPDATED", "PRODUCT_VARIANT", variantId,
                Map.of("from", variant.priceMinor(), "to", request.priceMinor()));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse updateVariantStatus(UUID actorId, UUID shopId, UUID productId, UUID variantId,
                                               UpdateVariantStatusRequest request) {
        ProductRow product = editable(shopId, productId, request.expectedVersion());
        VariantRow variant = requiredVariant(productId, variantId);
        if (request.status() == VariantStatus.INACTIVE && "ACTIVE".equals(product.status())
                && "ACTIVE".equals(variant.status()) && products.activeVariantCount(productId) <= 1) {
            throw conflict("PRODUCT_LAST_ACTIVE_VARIANT", "An active product needs at least one active SKU");
        }
        products.updateVariantStatus(variantId, request.status());
        advanceVersion(productId, request.expectedVersion());
        audit.record(actorId, shopId, "PRODUCT_VARIANT_STATUS_UPDATED", "PRODUCT_VARIANT", variantId,
                Map.of("from", variant.status(), "to", request.status().name()));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse addImage(UUID actorId, UUID shopId, UUID productId, AddImageRequest request) {
        editable(shopId, productId, request.expectedVersion());
        if (products.imageCount(productId) >= 30) throw conflict("PRODUCT_IMAGE_LIMIT", "A product may have at most 30 images");
        if (request.variantId() != null && !products.variantBelongsToProduct(request.variantId(), productId)) {
            throw imageNotFound();
        }
        UUID imageId = products.addImage(productId, request);
        advanceVersion(productId, request.expectedVersion());
        audit.record(actorId, shopId, "PRODUCT_IMAGE_ADDED", "PRODUCT_IMAGE", imageId,
                Map.of("productId", productId));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse deleteImage(UUID actorId, UUID shopId, UUID productId, UUID imageId,
                                       long expectedVersion) {
        ProductRow product = editable(shopId, productId, expectedVersion);
        ImageRow image = requiredImage(productId, imageId);
        if ("ACTIVE".equals(product.status()) && image.primary() && image.variantId() == null) {
            throw conflict("PRODUCT_PRIMARY_IMAGE_REQUIRED", "Set another primary product image before deleting this one");
        }
        products.deleteImage(imageId);
        advanceVersion(productId, expectedVersion);
        audit.record(actorId, shopId, "PRODUCT_IMAGE_DELETED", "PRODUCT_IMAGE", imageId,
                Map.of("productId", productId));
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse reorderImages(UUID actorId, UUID shopId, UUID productId, ReorderImagesRequest request) {
        editable(shopId, productId, request.expectedVersion());
        Set<UUID> ids = new HashSet<>();
        for (ImageOrderItem item : request.items()) {
            if (!ids.add(item.imageId())) throw bad("PRODUCT_IMAGE_DUPLICATED", "An image may appear only once");
            requiredImage(productId, item.imageId());
        }
        products.reorderImages(request.items());
        advanceVersion(productId, request.expectedVersion());
        audit.record(actorId, shopId, "PRODUCT_IMAGES_REORDERED", "PRODUCT", productId, Map.of());
        return products.response(productId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.product.update')")
    public ProductResponse setPrimaryImage(UUID actorId, UUID shopId, UUID productId, UUID imageId,
                                           SetPrimaryImageRequest request) {
        editable(shopId, productId, request.expectedVersion());
        ImageRow image = requiredImage(productId, imageId);
        products.setPrimaryImage(productId, image);
        advanceVersion(productId, request.expectedVersion());
        audit.record(actorId, shopId, "PRODUCT_PRIMARY_IMAGE_UPDATED", "PRODUCT_IMAGE", imageId,
                Map.of("productId", productId));
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
    public SliceResponse<ModerationCaseSummaryResponse> cases(String status, int limit, int offset) {
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
            if (selections.size() != variant.selections().size() || !selections.keySet().equals(optionTypes)) {
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

    private Map<String, OptionSelection> selectedOptions(UUID productId, Map<String, String> requested) {
        Map<String, OptionSelection> available = products.optionSelections(productId);
        Set<String> optionTypes = available.keySet().stream()
                .map(key -> key.substring(0, key.indexOf('='))).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> normalized = new LinkedHashMap<>();
        requested.forEach((type, code) -> {
            if (normalized.putIfAbsent(normalize(type), normalize(code)) != null) {
                throw bad("PRODUCT_VARIANT_OPTIONS_INVALID", "An option type may be selected only once");
            }
        });
        if (!normalized.keySet().equals(optionTypes)) {
            throw bad("PRODUCT_VARIANT_OPTIONS_INVALID", "Every SKU must select one value for every product option");
        }
        Map<String, OptionSelection> selected = new LinkedHashMap<>();
        normalized.forEach((type, code) -> {
            OptionSelection value = available.get(type + "=" + code);
            if (value == null) throw bad("PRODUCT_VARIANT_OPTIONS_INVALID", "SKU references an unknown option value");
            selected.put(type, value);
        });
        return selected;
    }

    private ProductRow editable(UUID shopId, UUID productId, long expectedVersion) {
        ProductRow row = products.rowForUpdate(productId).orElseThrow(ProductService::notFound);
        requireShop(row, shopId);
        requireVersion(row, expectedVersion);
        if (Set.of("HIDDEN", "REMOVED").contains(row.status())) {
            throw conflict("PRODUCT_MODERATION_LOCKED", "A moderated product cannot be edited");
        }
        return row;
    }

    private VariantRow requiredVariant(UUID productId, UUID variantId) {
        return products.variantForUpdate(productId, variantId).orElseThrow(ProductService::variantNotFound);
    }

    private ImageRow requiredImage(UUID productId, UUID imageId) {
        return products.imageForUpdate(productId, imageId).orElseThrow(ProductService::imageNotFound);
    }

    private void advanceVersion(UUID productId, long expectedVersion) {
        if (!products.bumpVersion(productId, expectedVersion)) throw versionConflict();
    }

    private static void requireVersion(ProductRow row, long expectedVersion) {
        if (row.version() != expectedVersion) throw versionConflict();
    }

    private static void validateCatalogQuery(PublicProductQuery query) {
        if (query.limit() < 1 || query.limit() > 100) {
            throw bad("PAGINATION_INVALID", "Limit must be 1-100");
        }
        if (query.query() != null && query.query().strip().length() > 200) {
            throw bad("PRODUCT_SEARCH_INVALID", "Search query may contain at most 200 characters");
        }
        if ((query.minPriceMinor() != null && query.minPriceMinor() < 0)
                || (query.maxPriceMinor() != null && query.maxPriceMinor() < 0)
                || (query.minPriceMinor() != null && query.maxPriceMinor() != null
                && query.minPriceMinor() > query.maxPriceMinor())) {
            throw bad("PRODUCT_PRICE_FILTER_INVALID", "Price range is invalid");
        }
    }

    private static String encodeCursor(ProductSort sort, CatalogEntry entry) {
        String value = switch (sort) {
            case NEWEST -> entry.publishedAt().toString();
            case PRICE_ASC, PRICE_DESC -> Long.toString(entry.price());
            case NAME_ASC -> entry.name();
        };
        String raw = sort.name() + "\0" + value + "\0" + entry.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static CatalogCursor decodeCursor(String cursor, ProductSort expectedSort) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\0", -1);
            if (parts.length != 3 || !expectedSort.name().equals(parts[0])) throw new IllegalArgumentException();
            UUID id = UUID.fromString(parts[2]);
            return switch (expectedSort) {
                case NEWEST -> new CatalogCursor(Instant.parse(parts[1]), null, null, id);
                case PRICE_ASC, PRICE_DESC -> new CatalogCursor(null, Long.parseLong(parts[1]), null, id);
                case NAME_ASC -> new CatalogCursor(null, null, parts[1], id);
            };
        } catch (IllegalArgumentException ex) {
            throw bad("PAGINATION_CURSOR_INVALID", "Cursor is invalid for the selected sort");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
    private static ApiException versionConflict() { return conflict("PRODUCT_VERSION_CONFLICT", "Product was changed by another request"); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found"); }
    private static ApiException variantNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_VARIANT_NOT_FOUND", "Product variant not found"); }
    private static ApiException imageNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_IMAGE_NOT_FOUND", "Product image not found"); }
    private static ApiException caseNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "MODERATION_CASE_NOT_FOUND", "Moderation case not found"); }
}
