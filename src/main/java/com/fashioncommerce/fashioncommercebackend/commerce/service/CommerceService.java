package com.fashioncommerce.fashioncommercebackend.commerce.service;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository.*;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CommerceService {
    private static final int MAX_DISTINCT_ITEMS = 100;
    private final CommerceRepository commerce;

    public CommerceService(CommerceRepository commerce) {
        this.commerce = commerce;
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'cart.manage_own', #buyerId)")
    public CartResponse cart(UUID buyerId) {
        commerce.ensureCart(buyerId);
        return response(buyerId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'cart.manage_own', #buyerId)")
    public CartResponse addItem(UUID buyerId, AddCartItemRequest request) {
        commerce.ensureCart(buyerId);
        CartRow cart = lockedCart(buyerId, request.expectedVersion());
        VariantSaleRow variant = commerce.variantForCart(request.variantId())
                .filter(VariantSaleRow::sellable)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "CART_VARIANT_UNAVAILABLE",
                        "This SKU is no longer available"));
        CartItemState existing = commerce.cartItemByVariant(cart.id(), request.variantId()).orElse(null);
        int quantity = request.quantity() + (existing == null ? 0 : existing.quantity());
        if (quantity > 99) throw new ApiException(HttpStatus.CONFLICT, "CART_QUANTITY_LIMIT",
                "A cart item cannot exceed 99 units");
        if (variant.availableQuantity() < quantity)
            throw new ApiException(HttpStatus.CONFLICT, "CART_STOCK_INSUFFICIENT",
                    "Requested quantity exceeds available stock");
        if (existing == null) {
            if (commerce.itemCount(cart.id()) >= MAX_DISTINCT_ITEMS)
                throw new ApiException(HttpStatus.CONFLICT, "CART_ITEM_LIMIT", "A cart cannot contain more than 100 SKUs");
            commerce.insertCartItem(cart.id(), variant.variantId(), quantity);
        } else {
            commerce.updateCartItem(existing.id(), quantity, true);
        }
        bump(cart);
        return response(buyerId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'cart.manage_own', #buyerId)")
    public CartResponse updateItem(UUID buyerId, UUID itemId, UpdateCartItemRequest request) {
        commerce.ensureCart(buyerId);
        CartRow cart = lockedCart(buyerId, request.expectedVersion());
        CartItemState item = item(cart.id(), itemId);
        commerce.updateCartItem(item.id(), request.quantity(), request.selected());
        bump(cart);
        return response(buyerId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'cart.manage_own', #buyerId)")
    public CartResponse deleteItem(UUID buyerId, UUID itemId, long expectedVersion) {
        commerce.ensureCart(buyerId);
        CartRow cart = lockedCart(buyerId, expectedVersion);
        commerce.deleteCartItem(item(cart.id(), itemId).id());
        bump(cart);
        return response(buyerId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'cart.manage_own', #buyerId)")
    public CartResponse updateSelection(UUID buyerId, UpdateCartSelectionRequest request) {
        commerce.ensureCart(buyerId);
        CartRow cart = lockedCart(buyerId, request.expectedVersion());
        Set<UUID> itemIds = new LinkedHashSet<>(request.itemIds());
        if (itemIds.size() != request.itemIds().size())
            throw new ApiException(HttpStatus.BAD_REQUEST, "CART_ITEM_IDS_DUPLICATED", "Cart item IDs must be unique");
        if (commerce.updateSelection(cart.id(), itemIds, request.selected()) != itemIds.size())
            throw new ApiException(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", "One or more cart items were not found");
        bump(cart);
        return response(buyerId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'checkout.create_own', #buyerId)")
    public CheckoutPreviewResponse checkoutPreview(UUID buyerId, CheckoutPreviewRequest request) {
        commerce.ensureCart(buyerId);
        CartRow cart = lockedCart(buyerId, request.expectedCartVersion());
        AddressRow address = commerce.address(buyerId, request.addressId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHECKOUT_ADDRESS_NOT_FOUND",
                        "Delivery address not found"));
        List<CartItemRow> selected = commerce.cartItems(cart.id()).stream().filter(CartItemRow::selected).toList();
        if (selected.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "CHECKOUT_CART_EMPTY",
                "Select at least one cart item before checkout");

        Map<UUID, Long> expectedPrices = request.expectedUnitPrices() == null
                ? Map.of() : request.expectedUnitPrices();
        List<CheckoutIssue> issues = new ArrayList<>();
        for (CartItemRow item : selected) validateCheckoutItem(item, expectedPrices.get(item.variantId()), issues);

        List<CheckoutShopSnapshot> shops = selected.stream()
                .collect(Collectors.groupingBy(CartItemRow::shopId, LinkedHashMap::new, Collectors.toList()))
                .values().stream().map(this::shopSnapshot).toList();
        long itemsSubtotal = shops.stream().mapToLong(CheckoutShopSnapshot::itemsSubtotalMinor).sum();
        long shippingFee = shops.stream().mapToLong(CheckoutShopSnapshot::shippingFeeMinor).sum();
        long grandTotal = Math.addExact(itemsSubtotal, shippingFee);
        RecipientSnapshot recipient = recipient(address);

        UUID snapshotId = null;
        Instant expiresAt = null;
        if (issues.isEmpty()) {
            expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
            commerce.expireReadySnapshots(buyerId);
            snapshotId = commerce.createCheckoutSnapshot(buyerId, cart.id(), cart.version(), recipient, shops,
                    itemsSubtotal, shippingFee, grandTotal, expiresAt);
        }
        return new CheckoutPreviewResponse(snapshotId, issues.isEmpty(), cart.version(), "VND", recipient, shops,
                itemsSubtotal, shippingFee, 0, grandTotal, List.copyOf(issues), expiresAt);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'address.manage_own', #buyerId)")
    public List<AddressResponse> addresses(UUID buyerId) {
        return commerce.addresses(buyerId).stream().map(this::addressResponse).toList();
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'address.manage_own', #buyerId)")
    public AddressResponse createAddress(UUID buyerId, CreateAddressRequest request) {
        boolean makeDefault = request.defaultAddress() || commerce.addressCount(buyerId) == 0;
        UUID id = commerce.createAddress(buyerId, request, makeDefault);
        return addressResponse(commerce.address(buyerId, id).orElseThrow());
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'address.manage_own', #buyerId)")
    public AddressResponse updateAddress(UUID buyerId, UUID addressId, UpdateAddressRequest request) {
        AddressRow before = commerce.address(buyerId, addressId)
                .orElseThrow(() -> addressNotFound());
        commerce.updateAddress(buyerId, addressId, request);
        if (before.defaultAddress() && !request.defaultAddress()) commerce.makeOldestAddressDefault(buyerId);
        return addressResponse(commerce.address(buyerId, addressId).orElseThrow());
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'address.manage_own', #buyerId)")
    public void deleteAddress(UUID buyerId, UUID addressId) {
        AddressRow address = commerce.address(buyerId, addressId).orElseThrow(() -> addressNotFound());
        commerce.deleteAddress(address.id());
        if (address.defaultAddress()) commerce.makeOldestAddressDefault(buyerId);
    }

    private CartResponse response(UUID buyerId) {
        CartRow cart = commerce.cart(buyerId).orElseThrow();
        List<CartItemRow> rows = commerce.cartItems(cart.id());
        List<CartShopResponse> shops = rows.stream()
                .collect(Collectors.groupingBy(CartItemRow::shopId, LinkedHashMap::new, Collectors.toList()))
                .values().stream().map(items -> new CartShopResponse(items.getFirst().shopId(),
                        items.getFirst().shopName(), items.getFirst().shopSlug(),
                        items.getFirst().defaultShippingFeeMinor(), items.stream().map(this::cartItemResponse).toList()))
                .toList();
        int totalQuantity = rows.stream().mapToInt(CartItemRow::quantity).sum();
        List<CartItemRow> selected = rows.stream().filter(CartItemRow::selected).toList();
        long subtotal = selected.stream().mapToLong(i -> Math.multiplyExact(i.priceMinor(), i.quantity())).sum();
        return new CartResponse(cart.id(), cart.version(), rows.size(), totalQuantity, selected.size(), subtotal,
                shops, cart.updatedAt());
    }

    private CartItemResponse cartItemResponse(CartItemRow item) {
        return new CartItemResponse(item.id(), item.variantId(), item.productId(), item.productName(),
                item.productSlug(), item.sellerSku(), item.options(), item.imageKey(), item.priceMinor(),
                item.compareAtPriceMinor(), item.quantity(), item.selected(), Math.max(0, item.availableQuantity()),
                availability(item));
    }

    private CheckoutShopSnapshot shopSnapshot(List<CartItemRow> items) {
        CartItemRow first = items.getFirst();
        List<CheckoutItemSnapshot> snapshots = items.stream().map(item -> new CheckoutItemSnapshot(
                item.id(), item.variantId(), item.productId(), item.productName(), item.sellerSku(),
                item.options(), item.imageKey(), item.priceMinor(), item.quantity(),
                Math.multiplyExact(item.priceMinor(), item.quantity()))).toList();
        long subtotal = snapshots.stream().mapToLong(CheckoutItemSnapshot::lineSubtotalMinor).sum();
        long shippingFee = first.defaultShippingFeeMinor();
        return new CheckoutShopSnapshot(first.shopId(), first.shopName(), first.shopSlug(), subtotal,
                shippingFee, Math.addExact(subtotal, shippingFee), snapshots);
    }

    private void validateCheckoutItem(CartItemRow item, Long expectedPrice, List<CheckoutIssue> issues) {
        if (!item.sellable()) {
            issues.add(issue(item, CheckoutIssueCode.PRODUCT_UNAVAILABLE,
                    "Product, SKU or shop is unavailable", expectedPrice));
        } else if (item.availableQuantity() <= 0) {
            issues.add(issue(item, CheckoutIssueCode.OUT_OF_STOCK, "SKU is out of stock", expectedPrice));
        } else if (item.availableQuantity() < item.quantity()) {
            issues.add(issue(item, CheckoutIssueCode.INSUFFICIENT_STOCK,
                    "Requested quantity exceeds available stock", expectedPrice));
        }
        if (expectedPrice != null && expectedPrice != item.priceMinor()) {
            issues.add(issue(item, CheckoutIssueCode.PRICE_CHANGED, "SKU price has changed", expectedPrice));
        }
    }

    private CheckoutIssue issue(CartItemRow item, CheckoutIssueCode code, String message, Long expectedPrice) {
        return new CheckoutIssue(item.id(), item.variantId(), code, message, expectedPrice, item.priceMinor(),
                item.quantity(), Math.max(0, item.availableQuantity()));
    }

    private CartAvailability availability(CartItemRow item) {
        if (!item.sellable()) return CartAvailability.UNAVAILABLE;
        if (item.availableQuantity() <= 0) return CartAvailability.OUT_OF_STOCK;
        if (item.availableQuantity() < item.quantity()) return CartAvailability.INSUFFICIENT_STOCK;
        return CartAvailability.AVAILABLE;
    }

    private CartRow lockedCart(UUID buyerId, long expectedVersion) {
        CartRow cart = commerce.cartForUpdate(buyerId).orElseThrow();
        if (cart.version() != expectedVersion) throw new ApiException(HttpStatus.CONFLICT,
                "CART_VERSION_CONFLICT", "Cart changed; reload it and retry");
        return cart;
    }

    private CartItemState item(UUID cartId, UUID itemId) {
        return commerce.cartItem(cartId, itemId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND", "Cart item not found"));
    }

    private void bump(CartRow cart) {
        if (!commerce.bumpCartVersion(cart.id(), cart.version()))
            throw new ApiException(HttpStatus.CONFLICT, "CART_VERSION_CONFLICT", "Cart changed; reload it and retry");
    }

    private RecipientSnapshot recipient(AddressRow value) {
        return new RecipientSnapshot(value.recipientName(), value.recipientPhone(), value.addressLine1(),
                value.addressLine2(), value.ward(), value.district(), value.province(), value.provinceCode(),
                value.postalCode(), value.countryCode());
    }

    private AddressResponse addressResponse(AddressRow value) {
        return new AddressResponse(value.id(), value.recipientName(), value.recipientPhone(), value.addressLine1(),
                value.addressLine2(), value.ward(), value.district(), value.province(), value.provinceCode(),
                value.postalCode(), value.countryCode(), value.defaultAddress(), value.createdAt(), value.updatedAt());
    }

    private ApiException addressNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ADDRESS_NOT_FOUND", "Address not found");
    }
}
