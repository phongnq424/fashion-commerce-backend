package com.fashioncommerce.fashioncommercebackend.commerce.service;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository.*;
import com.fashioncommerce.fashioncommercebackend.common.asset.AssetUrlResolver;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommerceServiceTests {
    private final CommerceRepository commerce = mock(CommerceRepository.class);
    private final AssetUrlResolver assetUrls = mock(AssetUrlResolver.class);
    private CommerceService service;
    private final UUID buyerId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");

    @BeforeEach
    void setUp() {
        reset(commerce, assetUrls);
        when(assetUrls.publicUrl(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key == null ? null : "https://cdn.example/" + key;
        });
        service = new CommerceService(commerce, assetUrls);
    }

    @Test
    void staleCartVersionRejectsMutation() {
        CartRow cart = new CartRow(cartId, buyerId, 3, now, now);
        when(commerce.cartForUpdate(buyerId)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> service.addItem(buyerId,
                new AddCartItemRequest(2L, UUID.randomUUID(), 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CART_VERSION_CONFLICT"));
        verify(commerce, never()).insertCartItem(any(), any(), anyInt());
    }

    @Test
    void unavailableSkuCannotBeAdded() {
        UUID variantId = UUID.randomUUID();
        CartRow cart = new CartRow(cartId, buyerId, 0, now, now);
        when(commerce.cartForUpdate(buyerId)).thenReturn(Optional.of(cart));
        when(commerce.variantForCart(variantId)).thenReturn(Optional.of(new VariantSaleRow(
                variantId, UUID.randomUUID(), UUID.randomUUID(), 199_000,
                "INACTIVE", "ACTIVE", "ACTIVE", 10)));

        assertThatThrownBy(() -> service.addItem(buyerId,
                new AddCartItemRequest(0L, variantId, 1)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("CART_VARIANT_UNAVAILABLE"));
    }

    @Test
    void addingExistingSkuIncrementsQuantityAndCartVersion() {
        UUID variantId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        CartRow before = new CartRow(cartId, buyerId, 0, now, now);
        CartRow after = new CartRow(cartId, buyerId, 1, now, now.plusSeconds(1));
        when(commerce.cartForUpdate(buyerId)).thenReturn(Optional.of(before));
        when(commerce.variantForCart(variantId)).thenReturn(Optional.of(new VariantSaleRow(
                variantId, UUID.randomUUID(), UUID.randomUUID(), 199_000,
                "ACTIVE", "ACTIVE", "ACTIVE", 10)));
        when(commerce.cartItemByVariant(cartId, variantId))
                .thenReturn(Optional.of(new CartItemState(itemId, variantId, 2, false)));
        when(commerce.bumpCartVersion(cartId, 0)).thenReturn(true);
        when(commerce.cart(buyerId)).thenReturn(Optional.of(after));
        when(commerce.cartItems(cartId)).thenReturn(List.of());

        CartResponse response = service.addItem(buyerId, new AddCartItemRequest(0L, variantId, 3));

        verify(commerce).updateCartItem(itemId, 5, true);
        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    void checkoutCreatesShortLivedSnapshotWithOneShippingFeePerShop() {
        CartRow cart = new CartRow(cartId, buyerId, 4, now, now);
        AddressRow address = address();
        UUID shopId = UUID.randomUUID();
        CartItemRow first = item(shopId, 2, 10, 120_000);
        CartItemRow second = item(shopId, 1, 10, 80_000);
        when(commerce.cartForUpdate(buyerId)).thenReturn(Optional.of(cart));
        when(commerce.address(buyerId, address.id())).thenReturn(Optional.of(address));
        when(commerce.cartItems(cartId)).thenReturn(List.of(first, second));
        UUID snapshotId = UUID.randomUUID();
        when(commerce.createCheckoutSnapshot(eq(buyerId), eq(cartId), eq(4L), any(), anyList(),
                eq(320_000L), eq(30_000L), eq(350_000L), any())).thenReturn(snapshotId);

        CheckoutPreviewResponse result = service.checkoutPreview(buyerId,
                new CheckoutPreviewRequest(4L, address.id(), Map.of(
                        first.variantId(), 120_000L, second.variantId(), 80_000L)));

        assertThat(result.readyForOrder()).isTrue();
        assertThat(result.snapshotId()).isEqualTo(snapshotId);
        assertThat(result.itemsSubtotalMinor()).isEqualTo(320_000);
        assertThat(result.shippingFeeMinor()).isEqualTo(30_000);
        assertThat(result.shops()).singleElement().satisfies(shop -> assertThat(shop.items()).hasSize(2));
        verify(commerce).expireReadySnapshots(buyerId);
    }

    @Test
    void checkoutReportsPriceAndStockChangesWithoutCreatingSnapshot() {
        CartRow cart = new CartRow(cartId, buyerId, 2, now, now);
        AddressRow address = address();
        CartItemRow item = item(UUID.randomUUID(), 5, 2, 150_000);
        when(commerce.cartForUpdate(buyerId)).thenReturn(Optional.of(cart));
        when(commerce.address(buyerId, address.id())).thenReturn(Optional.of(address));
        when(commerce.cartItems(cartId)).thenReturn(List.of(item));

        CheckoutPreviewResponse result = service.checkoutPreview(buyerId,
                new CheckoutPreviewRequest(2L, address.id(), Map.of(item.variantId(), 140_000L)));

        assertThat(result.readyForOrder()).isFalse();
        assertThat(result.snapshotId()).isNull();
        assertThat(result.issues()).extracting(CheckoutIssue::code)
                .containsExactly(CheckoutIssueCode.INSUFFICIENT_STOCK, CheckoutIssueCode.PRICE_CHANGED);
        verify(commerce, never()).createCheckoutSnapshot(any(), any(), anyLong(), any(), anyList(),
                anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void firstAddressIsAlwaysDefault() {
        CreateAddressRequest request = new CreateAddressRequest("Buyer", "0900000000", "1 Main St",
                null, "Ward", "District", "HCM", "79", null, "VN", false);
        UUID addressId = UUID.randomUUID();
        when(commerce.addressCount(buyerId)).thenReturn(0);
        when(commerce.createAddress(buyerId, request, true)).thenReturn(addressId);
        AddressRow row = new AddressRow(addressId, buyerId, "Buyer", "0900000000", "1 Main St",
                null, "Ward", "District", "HCM", "79", null, "VN", true, now, now);
        when(commerce.address(buyerId, addressId)).thenReturn(Optional.of(row));

        assertThat(service.createAddress(buyerId, request).defaultAddress()).isTrue();
        verify(commerce).createAddress(buyerId, request, true);
    }

    private AddressRow address() {
        return new AddressRow(UUID.randomUUID(), buyerId, "Buyer", "0900000000", "1 Main St",
                null, "Ward", "District", "HCM", "79", null, "VN", true, now, now);
    }

    private CartItemRow item(UUID shopId, int quantity, int available, long price) {
        return new CartItemRow(UUID.randomUUID(), cartId, UUID.randomUUID(), UUID.randomUUID(), shopId,
                "Shop", "shop", 30_000, "Product", "product", "SKU", Map.of("SIZE", "M"),
                "products/main.jpg", price, null, quantity, true, available,
                "ACTIVE", "ACTIVE", "ACTIVE");
    }
}
