package com.fashioncommerce.fashioncommercebackend.commerce.service;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentMethodStrategy;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentMethodStrategy.InventoryMode;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentMethodStrategy.PaymentPreparation;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentStrategyRegistry;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository.CartRow;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository.CheckoutSnapshotRow;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.OrderRepository;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.OrderRepository.IdempotencyRow;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.OrderRepository.VariantOrderRow;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTests {
    private final CommerceRepository commerce = mock(CommerceRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentStrategyRegistry payments = mock(PaymentStrategyRegistry.class);
    private final PaymentMethodStrategy cod = mock(PaymentMethodStrategy.class);
    private OrderService service;
    private final UUID buyerId = UUID.randomUUID();
    private final UUID cartId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final Instant now = Instant.now();

    @BeforeEach
    void setUp() {
        reset(commerce, orders, payments, cod);
        service = new OrderService(commerce, orders, payments);
    }

    @Test
    void idempotentReplayReturnsExistingOrderWithoutTouchingCheckoutOrStock() {
        PlaceOrderRequest request = request();
        UUID orderId = UUID.randomUUID();
        OrderResponse response = response(orderId);
        when(orders.claimIdempotency(eq(buyerId), eq("checkout-123"), anyString()))
                .thenAnswer(invocation -> new IdempotencyRow(buyerId, "checkout-123", invocation.getArgument(2),
                        orderId, now, now.plusSeconds(3600)));
        when(orders.order(buyerId, orderId)).thenReturn(Optional.of(response));

        assertThat(service.place(buyerId, "checkout-123", request)).isSameAs(response);
        verifyNoInteractions(commerce, payments);
        verify(orders, never()).lockVariants(any());
    }

    @Test
    void reusingKeyForDifferentPayloadIsRejected() {
        when(orders.claimIdempotency(eq(buyerId), eq("checkout-123"), anyString()))
                .thenReturn(new IdempotencyRow(buyerId, "checkout-123", "0".repeat(64),
                        UUID.randomUUID(), now, now.plusSeconds(3600)));

        assertThatThrownBy(() -> service.place(buyerId, "checkout-123", request()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        verifyNoInteractions(commerce, payments);
    }

    @Test
    void codPlacementLocksAndCommitsStockThenConsumesSelectedCartItems() {
        PlaceOrderRequest request = request();
        CheckoutSnapshotRow snapshot = snapshot();
        CheckoutItemSnapshot item = snapshot.shops().getFirst().items().getFirst();
        String requestHash = hash(request);
        when(orders.claimIdempotency(buyerId, "checkout-123", requestHash))
                .thenReturn(new IdempotencyRow(buyerId, "checkout-123", requestHash, null,
                        now, now.plusSeconds(3600)));
        when(commerce.checkoutSnapshot(buyerId, snapshotId, true)).thenReturn(Optional.of(snapshot));
        when(commerce.cartForUpdate(buyerId)).thenReturn(Optional.of(
                new CartRow(cartId, buyerId, 4, now, now)));
        when(payments.required(PaymentMethod.COD)).thenReturn(cod);
        when(cod.availability(any())).thenReturn(new PaymentMethodResponse(PaymentMethod.COD,
                "COD", "COD", true, true, PaymentMethodStatus.AVAILABLE, null));
        when(cod.prepare(any())).thenReturn(new PaymentPreparation("COD", "AWAITING_COLLECTION",
                "CONFIRMED", "PENDING_CONFIRMATION", InventoryMode.COMMIT, null, null, null));
        when(orders.lockVariants(Set.of(item.variantId()))).thenReturn(List.of(new VariantOrderRow(
                item.variantId(), item.productId(), snapshot.shops().getFirst().shopId(), item.unitPriceMinor(),
                "ACTIVE", "ACTIVE", "ACTIVE", 10, 1)));
        when(commerce.consumeCheckoutSnapshot(eq(snapshotId), any())).thenReturn(true);
        when(commerce.deleteCartItems(cartId, Set.of(item.cartItemId()))).thenReturn(1);
        when(commerce.bumpCartVersion(cartId, 4)).thenReturn(true);
        when(orders.order(eq(buyerId), any())).thenAnswer(invocation ->
                Optional.of(response(invocation.getArgument(1))));

        OrderResponse result = service.place(buyerId, "checkout-123", request);

        assertThat(result.status()).isEqualTo("CONFIRMED");
        verify(orders).applyInventory(anyList(), anyMap(), eq(InventoryMode.COMMIT), isNull(), any());
        verify(commerce).consumeCheckoutSnapshot(eq(snapshotId), eq(result.id()));
        verify(orders).completeIdempotency(buyerId, "checkout-123", result.id());
    }

    private PlaceOrderRequest request() {
        return new PlaceOrderRequest(snapshotId, PaymentMethod.COD, "Leave at reception", null);
    }

    private CheckoutSnapshotRow snapshot() {
        UUID shopId = UUID.randomUUID();
        CheckoutItemSnapshot item = new CheckoutItemSnapshot(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "Product", "SKU", Map.of("SIZE", "M"), null, null,
                100_000, 2, 200_000);
        CheckoutShopSnapshot shop = new CheckoutShopSnapshot(shopId, "Shop", "shop", 200_000,
                30_000, 230_000, List.of(item));
        RecipientSnapshot recipient = new RecipientSnapshot("Buyer", "0900000000", "Address", null,
                "Ward", "District", "HCM", "79", null, "VN");
        return new CheckoutSnapshotRow(snapshotId, buyerId, cartId, 4, "VND", recipient,
                List.of(shop), 200_000, 30_000, 0, 230_000, "READY",
                now.plusSeconds(900), null, null, now);
    }

    private OrderResponse response(UUID id) {
        return new OrderResponse(id, "ORD-TEST", "CONFIRMED", "VND", PaymentMethod.COD,
                snapshot().recipient(), 200_000, 30_000, 0, 230_000, "Leave at reception",
                now, null, null, null, List.of(), null);
    }

    private String hash(PlaceOrderRequest request) {
        try {
            String value = request.checkoutSnapshotId() + "\n" + request.paymentMethod() + "\n"
                    + request.buyerNote().strip() + "\n";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
