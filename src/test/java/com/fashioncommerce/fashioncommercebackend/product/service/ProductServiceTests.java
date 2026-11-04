package com.fashioncommerce.fashioncommercebackend.product.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.audit.ModerationActionRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.*;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.CatalogEntry;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.ImageRow;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.InventoryRow;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.OptionSelection;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.ProductRow;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.VariantRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductServiceTests {
    private final ProductRepository products = mock(ProductRepository.class);
    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final ModerationActionRepository moderation = mock(ModerationActionRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private ProductService service;

    @BeforeEach
    void setUp() {
        reset(products, audit, moderation, notifications);
        service = new ProductService(products, audit, moderation, notifications);
    }

    @Test
    void createRejectsDuplicatedSkuBeforeWritingAnything() {
        UUID shopId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(products.activeCategory(categoryId)).thenReturn(true);
        CreateProductRequest request = request(categoryId, List.of(
                variant("AO-TRANG-M", "M", 10),
                variant("ao-trang-m", "L", 5)));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), shopId, request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_SKU_DUPLICATED"));
        verify(products, never()).create(any(), any(), any(), any());
    }

    @Test
    void createRejectsSkuSelectingUnknownOptionValue() {
        UUID categoryId = UUID.randomUUID();
        when(products.activeCategory(categoryId)).thenReturn(true);
        CreateProductRequest request = request(categoryId, List.of(variant("AO-XL", "XL", 2)));

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), UUID.randomUUID(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_VARIANT_OPTIONS_INVALID"));
    }

    @Test
    void publishRequiresCompleteSellableAggregate() {
        UUID actorId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        ProductRow draft = row(shopId, "DRAFT");
        when(products.rowForUpdate(draft.id())).thenReturn(Optional.of(draft));
        when(products.readyToPublish(draft.id())).thenReturn(false);

        assertThatThrownBy(() -> service.publish(actorId, shopId, draft.id(), draft.version()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_PUBLISHABLE"));
        verify(products, never()).publish(any(), anyLong());
    }

    @Test
    void inventoryCannotBeReducedBelowReservedQuantity() {
        UUID shopId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        InventoryRow inventory = new InventoryRow(variantId, UUID.randomUUID(), 12, 4, 2);
        when(products.inventoryForUpdate(variantId, shopId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> service.setInventory(UUID.randomUUID(), shopId, variantId,
                new InventoryRequest(3, 2, "manual adjustment")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVENTORY_BELOW_RESERVED"));
        verify(products, never()).setInventory(any(), any(), any(), any());
    }

    @Test
    void draftProductCannotBeReported() {
        ProductRow draft = row(UUID.randomUUID(), "DRAFT");
        when(products.row(draft.id())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.report(UUID.randomUUID(), draft.id(),
                new ReportProductRequest(ReportReason.COUNTERFEIT, "suspected counterfeit", List.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_NOT_REPORTABLE"));
        verify(products, never()).createCase(any(), any(), any());
    }

    @Test
    void moderatorCanHideProductAndResolveLinkedCase() {
        UUID moderatorId = UUID.randomUUID();
        ProductRow active = row(UUID.randomUUID(), "ACTIVE");
        ProductRow hidden = withStatus(active, "HIDDEN");
        ModerationCaseResponse moderationCase = moderationCase(active);
        ProductResponse expected = mock(ProductResponse.class);
        when(products.rowForUpdate(active.id())).thenReturn(Optional.of(active));
        when(products.moderationCaseForUpdate(moderationCase.id())).thenReturn(Optional.of(moderationCase));
        when(products.moderateProduct(active.id(), ModerationAction.HIDE)).thenReturn(true);
        when(products.resolveCase(moderationCase.id(), moderatorId, "HIDE", "Counterfeit evidence verified"))
                .thenReturn(true);
        when(products.row(active.id())).thenReturn(Optional.of(hidden));
        when(products.response(active.id())).thenReturn(expected);

        ProductResponse result = service.moderate(moderatorId, active.id(),
                new ModerateProductRequest(moderationCase.id(), ModerationAction.HIDE,
                        "Counterfeit evidence verified"));

        assertThat(result).isSameAs(expected);
        verify(moderation).productDecision(moderatorId, active.id(), "HIDE",
                "Counterfeit evidence verified", "ACTIVE", "HIDDEN");
        verify(notifications).create(eq(active.ownerUserId()), eq("PRODUCT_MODERATION_HIDE"),
                anyString(), anyString(), anyMap());
    }

    @Test
    void staleProductVersionRejectsUpdateBeforeWriting() {
        ProductRow product = withVersion(row(UUID.randomUUID(), "DRAFT"), 3L);
        when(products.rowForUpdate(product.id())).thenReturn(Optional.of(product));
        UpdateProductRequest request = new UpdateProductRequest(2L, null, null, null,
                "Updated name", null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), product.shopId(), product.id(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_VERSION_CONFLICT"));
        verify(products, never()).update(any(), any());
    }

    @Test
    void createVariantRejectsUnknownOptionSelection() {
        ProductRow product = row(UUID.randomUUID(), "DRAFT");
        when(products.rowForUpdate(product.id())).thenReturn(Optional.of(product));
        when(products.optionSelections(product.id())).thenReturn(Map.of(
                "SIZE=M", new OptionSelection(UUID.randomUUID(), UUID.randomUUID())));
        CreateVariantRequest request = new CreateVariantRequest(product.version(), "AO-XL", null,
                Map.of("SIZE", "XL"), 199_000, null, 250, null, null, null, 0, 2);

        assertThatThrownBy(() -> service.createVariant(UUID.randomUUID(), product.shopId(), product.id(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_VARIANT_OPTIONS_INVALID"));
        verify(products, never()).createVariant(any(), any(), any(), any(), anyMap());
    }

    @Test
    void activeProductCannotDisableItsLastActiveVariant() {
        ProductRow product = row(UUID.randomUUID(), "ACTIVE");
        UUID variantId = UUID.randomUUID();
        VariantRow variant = new VariantRow(variantId, product.id(), product.shopId(), "AO-M",
                "ACTIVE", 199_000, null, 0);
        when(products.rowForUpdate(product.id())).thenReturn(Optional.of(product));
        when(products.variantForUpdate(product.id(), variantId)).thenReturn(Optional.of(variant));
        when(products.activeVariantCount(product.id())).thenReturn(1);

        assertThatThrownBy(() -> service.updateVariantStatus(UUID.randomUUID(), product.shopId(), product.id(),
                variantId, new UpdateVariantStatusRequest(product.version(), VariantStatus.INACTIVE)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_LAST_ACTIVE_VARIANT"));
        verify(products, never()).updateVariantStatus(any(), any());
    }

    @Test
    void variantCompareAtPriceCannotBeLowerThanSellingPrice() {
        ProductRow product = row(UUID.randomUUID(), "DRAFT");
        UUID variantId = UUID.randomUUID();
        VariantRow variant = new VariantRow(variantId, product.id(), product.shopId(), "AO-M",
                "ACTIVE", 199_000, 249_000L, 0);
        when(products.rowForUpdate(product.id())).thenReturn(Optional.of(product));
        when(products.variantForUpdate(product.id(), variantId)).thenReturn(Optional.of(variant));

        assertThatThrownBy(() -> service.updateVariantPrice(UUID.randomUUID(), product.shopId(), product.id(),
                variantId, new UpdateVariantPriceRequest(product.version(), 200_000, 199_000L)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_COMPARE_PRICE_INVALID"));
        verify(products, never()).updateVariantPrice(any(), any());
    }

    @Test
    void activeProductCannotDeleteItsPrimaryProductImage() {
        ProductRow product = row(UUID.randomUUID(), "ACTIVE");
        UUID imageId = UUID.randomUUID();
        when(products.rowForUpdate(product.id())).thenReturn(Optional.of(product));
        when(products.imageForUpdate(product.id(), imageId))
                .thenReturn(Optional.of(new ImageRow(imageId, product.id(), null, true)));

        assertThatThrownBy(() -> service.deleteImage(UUID.randomUUID(), product.shopId(), product.id(),
                imageId, product.version())).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_PRIMARY_IMAGE_REQUIRED"));
        verify(products, never()).deleteImage(any());
    }

    @Test
    void publicCatalogReturnsOpaqueCursorAndDecodesItForNextPage() {
        Instant published = Instant.parse("2026-10-28T00:00:00Z");
        PublicProductSummaryResponse first = mock(PublicProductSummaryResponse.class);
        PublicProductSummaryResponse second = mock(PublicProductSummaryResponse.class);
        CatalogEntry firstEntry = new CatalogEntry(first, published, 100_000, "ao", UUID.randomUUID());
        CatalogEntry secondEntry = new CatalogEntry(second, published.minusSeconds(1), 120_000, "quan", UUID.randomUUID());
        when(products.activeProducts(any(), isNull(), eq(2))).thenReturn(List.of(firstEntry, secondEntry));
        PublicProductQuery query = new PublicProductQuery(null, null, null, null,
                null, null, false, ProductSort.NEWEST, null, 1);

        var page = service.publicProducts(query);
        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.hasMore()).isTrue();

        reset(products);
        when(products.activeProducts(any(), any(), eq(2))).thenReturn(List.of());
        service.publicProducts(new PublicProductQuery(null, null, null, null,
                null, null, false, ProductSort.NEWEST, page.nextCursor(), 1));
        var cursor = org.mockito.ArgumentCaptor.forClass(ProductRepository.CatalogCursor.class);
        verify(products).activeProducts(any(), cursor.capture(), eq(2));
        assertThat(cursor.getValue().publishedAt()).isEqualTo(firstEntry.publishedAt());
        assertThat(cursor.getValue().id()).isEqualTo(firstEntry.id());
    }

    private CreateProductRequest request(UUID categoryId, List<VariantRequest> variants) {
        OptionRequest sizes = new OptionRequest(OptionType.SIZE, "Kích thước", 0, List.of(
                new OptionValueRequest("M", "M", null, 0),
                new OptionValueRequest("L", "L", null, 1)));
        return new CreateProductRequest(categoryId, null, "AO-01", "Áo thun", "Áo thun cotton",
                "Cotton", "Regular", Gender.UNISEX, null, null, List.of(sizes), variants,
                List.of(new ImageRequest("products/ao-01/main.jpg", "Áo thun", 0, true, null)));
    }

    private VariantRequest variant(String sku, String size, int stock) {
        return new VariantRequest(sku, null, Map.of("SIZE", size), 199_000L, 249_000L,
                250, null, null, null, stock, 2);
    }

    private ProductRow row(UUID shopId, String status) {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        return new ProductRow(UUID.randomUUID(), shopId, UUID.randomUUID(), null, "AO-01",
                "Áo thun", "ao-thun-12345678", "Mô tả", "Cotton", "Regular", "UNISEX",
                null, null, 0L, status, "ACTIVE".equals(status) ? now : null, UUID.randomUUID(), now, now);
    }

    private ProductRow withStatus(ProductRow row, String status) {
        return new ProductRow(row.id(), row.shopId(), row.categoryId(), row.brandId(), row.sellerProductCode(),
                row.name(), row.slug(), row.description(), row.material(), row.fitType(), row.targetGender(),
                row.careInstructions(), row.sizeChart(), row.version(), status, row.publishedAt(), row.ownerUserId(),
                row.createdAt(), row.updatedAt());
    }

    private ProductRow withVersion(ProductRow row, long version) {
        return new ProductRow(row.id(), row.shopId(), row.categoryId(), row.brandId(), row.sellerProductCode(),
                row.name(), row.slug(), row.description(), row.material(), row.fitType(), row.targetGender(),
                row.careInstructions(), row.sizeChart(), version, row.status(), row.publishedAt(), row.ownerUserId(),
                row.createdAt(), row.updatedAt());
    }

    private ModerationCaseResponse moderationCase(ProductRow product) {
        Instant now = Instant.parse("2026-10-28T00:00:00Z");
        return new ModerationCaseResponse(UUID.randomUUID(), product.id(), product.name(), product.shopId(),
                UUID.randomUUID(), "COUNTERFEIT", "Suspected counterfeit", List.of(), "OPEN",
                null, null, null, null, now, now);
    }
}
