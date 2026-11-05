package com.fashioncommerce.fashioncommercebackend.commerce.service;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentMethodStrategy;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentMethodStrategy.PaymentContext;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentMethodStrategy.PaymentPreparation;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentStrategyRegistry;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository.CartRow;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository.CheckoutSnapshotRow;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.OrderRepository;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.OrderRepository.*;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
public class OrderService {
    private final CommerceRepository commerce;
    private final OrderRepository orders;
    private final PaymentStrategyRegistry payments;

    public OrderService(CommerceRepository commerce, OrderRepository orders, PaymentStrategyRegistry payments) {
        this.commerce = commerce;
        this.orders = orders;
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'checkout.create_own', #buyerId)")
    public List<PaymentMethodResponse> paymentMethods(UUID buyerId, UUID snapshotId) {
        CheckoutSnapshotRow snapshot = snapshot(buyerId, snapshotId, false);
        requireReady(snapshot);
        PaymentContext context = context(buyerId, snapshot, null, Instant.now());
        return payments.all().stream().map(strategy -> strategy.availability(context)).toList();
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'checkout.create_own', #buyerId)")
    public OrderResponse place(UUID buyerId, String idempotencyKey, PlaceOrderRequest request) {
        String key = validIdempotencyKey(idempotencyKey);
        String requestHash = requestHash(request);
        IdempotencyRow claim = orders.claimIdempotency(buyerId, key, requestHash);
        if (!MessageDigest.isEqual(claim.requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used for a different request");
        }
        if (claim.orderId() != null) return requiredOrder(buyerId, claim.orderId());

        CheckoutSnapshotRow snapshot = snapshot(buyerId, request.checkoutSnapshotId(), true);
        requireReady(snapshot);
        CartRow cart = commerce.cartForUpdate(buyerId).orElseThrow(() -> checkoutChanged("Cart no longer exists"));
        if (!cart.id().equals(snapshot.cartId()) || cart.version() != snapshot.cartVersion()) {
            throw checkoutChanged("Cart changed after checkout preview");
        }
        validateSnapshotTotals(snapshot);

        Instant now = Instant.now();
        PaymentMethodStrategy strategy = payments.required(request.paymentMethod());
        PaymentContext paymentContext = context(buyerId, snapshot, request.returnUrl(), now);
        PaymentMethodResponse availability = strategy.availability(paymentContext);
        if (!availability.available()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_METHOD_UNAVAILABLE",
                    availability.unavailableReason());
        }
        PaymentPreparation preparation = strategy.prepare(paymentContext);

        List<CheckoutItemWithShop> snapshotItems = flatten(snapshot.shops());
        Map<UUID, VariantOrderRow> variants = lockAndValidateVariants(snapshotItems);
        UUID orderId = UUID.randomUUID();
        String orderNumber = number("ORD");
        NewOrder order = new NewOrder(orderId, orderNumber, buyerId, snapshot.currency(), snapshot.recipient(),
                request.paymentMethod(), preparation.orderStatus(), snapshot.itemsSubtotalMinor(),
                snapshot.shippingFeeMinor(), snapshot.discountTotalMinor(), snapshot.grandTotalMinor(),
                request.buyerNote());
        orders.insertOrder(order);

        Map<UUID, UUID> shopOrderIds = new LinkedHashMap<>();
        List<NewShopOrder> shopOrders = new ArrayList<>();
        int shopSequence = 1;
        for (CheckoutShopSnapshot shop : snapshot.shops()) {
            UUID shopOrderId = UUID.randomUUID();
            shopOrderIds.put(shop.shopId(), shopOrderId);
            shopOrders.add(new NewShopOrder(shopOrderId, orderId, shop.shopId(),
                    orderNumber + "-S%02d".formatted(shopSequence++), preparation.shopOrderStatus(),
                    shop.itemsSubtotalMinor(), shop.shippingFeeMinor(), 0, shop.totalMinor(), buyerId));
        }
        orders.insertShopOrders(shopOrders);

        List<NewOrderItem> orderItems = snapshotItems.stream().map(value -> {
            CheckoutItemSnapshot item = value.item();
            return new NewOrderItem(UUID.randomUUID(), shopOrderIds.get(value.shopId()), orderId, buyerId,
                    value.shopId(), item.productId(), item.variantId(), item.sellerSku(), item.productName(),
                    item.options(), item.unitPriceMinor(), item.quantity(), 0, item.lineSubtotalMinor());
        }).toList();
        orders.insertOrderItems(orderItems);
        orders.applyInventory(orderItems, variants, preparation.inventoryMode(), preparation.expiresAt(), now);
        orders.insertPaymentIntent(orderId, orderNumber + "-" + request.paymentMethod(), request.paymentMethod(),
                preparation.provider(), snapshot.grandTotalMinor(), snapshot.currency(), preparation.paymentStatus(),
                preparation.providerPaymentId(), preparation.checkoutUrl(), preparation.expiresAt());

        if (!commerce.consumeCheckoutSnapshot(snapshot.id(), orderId)) {
            throw checkoutChanged("Checkout snapshot is no longer available");
        }
        Set<UUID> cartItemIds = new LinkedHashSet<>(snapshotItems.stream()
                .map(value -> value.item().cartItemId()).toList());
        if (commerce.deleteCartItems(cart.id(), cartItemIds) != cartItemIds.size()
                || !commerce.bumpCartVersion(cart.id(), cart.version())) {
            throw checkoutChanged("Cart changed while placing the order");
        }
        orders.completeIdempotency(buyerId, key, orderId);
        return requiredOrder(buyerId, orderId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'order.read_own', #buyerId)")
    public OrderResponse order(UUID buyerId, UUID orderId) {
        return requiredOrder(buyerId, orderId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'order.read_own', #buyerId)")
    public SliceResponse<OrderSummaryResponse> orders(UUID buyerId, int limit, int offset) {
        validatePage(limit, offset);
        List<OrderSummaryResponse> rows = orders.orderSummaries(buyerId, limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        return new SliceResponse<>(hasMore ? rows.subList(0, limit) : rows, limit, offset, hasMore);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'order.read_own', #buyerId)")
    public PaymentResponse payment(UUID buyerId, UUID paymentId) {
        return orders.paymentForBuyer(buyerId, paymentId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found"));
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'order.cancel_own', #buyerId)")
    public OrderResponse cancel(UUID buyerId, UUID orderId, CancelOrderRequest request) {
        OrderRow order = orders.orderForUpdate(buyerId, orderId).orElseThrow(OrderService::orderNotFound);
        if ("CANCELLED".equals(order.status())) return requiredOrder(buyerId, orderId);
        if (!Set.of("PENDING_PAYMENT", "CONFIRMED").contains(order.status())
                || !orders.allShopOrdersCancellable(orderId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE",
                    "The order can no longer be cancelled");
        }
        List<ReservationRow> reservations = orders.reservationsForUpdate(orderId);
        orders.releaseInventoryForCancellation(reservations, buyerId);
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "Cancelled by buyer" : request.reason().strip();
        orders.cancelOrder(orderId, buyerId, order.status(), reason);
        return requiredOrder(buyerId, orderId);
    }

    private Map<UUID, VariantOrderRow> lockAndValidateVariants(List<CheckoutItemWithShop> items) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (CheckoutItemWithShop value : items) {
            if (!ids.add(value.item().variantId())) throw checkoutChanged("Checkout contains a duplicated SKU");
        }
        Map<UUID, VariantOrderRow> variants = orders.lockVariants(ids).stream()
                .collect(java.util.stream.Collectors.toMap(VariantOrderRow::variantId, value -> value));
        if (variants.size() != ids.size()) throw checkoutChanged("One or more SKUs no longer exist");
        for (CheckoutItemWithShop value : items) {
            CheckoutItemSnapshot item = value.item();
            VariantOrderRow variant = variants.get(item.variantId());
            if (!variant.sellable() || !variant.shopId().equals(value.shopId())
                    || !variant.productId().equals(item.productId())) {
                throw checkoutChanged("One or more products are no longer available");
            }
            if (variant.priceMinor() != item.unitPriceMinor()) {
                throw checkoutChanged("One or more prices changed after checkout preview");
            }
            if (variant.availableQuantity() < item.quantity()) {
                throw new ApiException(HttpStatus.CONFLICT, "ORDER_STOCK_INSUFFICIENT",
                        "One or more SKUs no longer have enough stock");
            }
        }
        return variants;
    }

    private List<CheckoutItemWithShop> flatten(List<CheckoutShopSnapshot> shops) {
        if (shops == null || shops.isEmpty()) throw checkoutChanged("Checkout contains no shop");
        List<CheckoutItemWithShop> result = new ArrayList<>();
        for (CheckoutShopSnapshot shop : shops) {
            if (shop.items() == null || shop.items().isEmpty()) throw checkoutChanged("Checkout contains an empty shop");
            for (CheckoutItemSnapshot item : shop.items()) result.add(new CheckoutItemWithShop(shop.shopId(), item));
        }
        return result;
    }

    private void validateSnapshotTotals(CheckoutSnapshotRow snapshot) {
        if (snapshot.discountTotalMinor() != 0) {
            throw checkoutChanged("Checkout discounts are not supported yet");
        }
        long itemsSubtotal = 0;
        long shipping = 0;
        for (CheckoutShopSnapshot shop : snapshot.shops()) {
            long shopItems = shop.items().stream().mapToLong(item ->
                    Math.multiplyExact(item.unitPriceMinor(), item.quantity())).sum();
            if (shopItems != shop.itemsSubtotalMinor()
                    || Math.addExact(shopItems, shop.shippingFeeMinor()) != shop.totalMinor()) {
                throw checkoutChanged("Checkout totals are inconsistent");
            }
            itemsSubtotal = Math.addExact(itemsSubtotal, shopItems);
            shipping = Math.addExact(shipping, shop.shippingFeeMinor());
        }
        if (itemsSubtotal != snapshot.itemsSubtotalMinor() || shipping != snapshot.shippingFeeMinor()
                || Math.subtractExact(Math.addExact(itemsSubtotal, shipping), snapshot.discountTotalMinor())
                != snapshot.grandTotalMinor()) {
            throw checkoutChanged("Checkout totals are inconsistent");
        }
    }

    private CheckoutSnapshotRow snapshot(UUID buyerId, UUID snapshotId, boolean lock) {
        return commerce.checkoutSnapshot(buyerId, snapshotId, lock).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CHECKOUT_SNAPSHOT_NOT_FOUND", "Checkout snapshot not found"));
    }

    private void requireReady(CheckoutSnapshotRow snapshot) {
        if (snapshot.expiresAt().isBefore(Instant.now()) || snapshot.expiresAt().equals(Instant.now())) {
            throw new ApiException(HttpStatus.GONE, "CHECKOUT_SNAPSHOT_EXPIRED",
                    "Checkout snapshot expired; create a new preview");
        }
        if (!"READY".equals(snapshot.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHECKOUT_SNAPSHOT_NOT_READY",
                    "Checkout snapshot was already consumed or expired");
        }
    }

    private PaymentContext context(UUID buyerId, CheckoutSnapshotRow snapshot, String returnUrl, Instant now) {
        return new PaymentContext(buyerId, snapshot.id(), snapshot.grandTotalMinor(), snapshot.currency(),
                returnUrl == null || returnUrl.isBlank() ? null : returnUrl.strip(), now);
    }

    private OrderResponse requiredOrder(UUID buyerId, UUID orderId) {
        return orders.order(buyerId, orderId).orElseThrow(OrderService::orderNotFound);
    }

    private static String validIdempotencyKey(String value) {
        String key = value == null ? "" : value.strip();
        if (!key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key must be 8-128 URL-safe characters");
        }
        return key;
    }

    private static String requestHash(PlaceOrderRequest request) {
        String value = request.checkoutSnapshotId() + "\n" + request.paymentMethod() + "\n"
                + normalized(request.buyerNote()) + "\n" + normalized(request.returnUrl());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String normalized(String value) { return value == null ? "" : value.strip(); }

    private static String number(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGINATION_INVALID",
                    "Limit must be 1-100 and offset must be non-negative");
        }
    }

    private static ApiException checkoutChanged(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CHECKOUT_CHANGED", message);
    }

    private static ApiException orderNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found");
    }

    private record CheckoutItemWithShop(UUID shopId, CheckoutItemSnapshot item) { }
}
