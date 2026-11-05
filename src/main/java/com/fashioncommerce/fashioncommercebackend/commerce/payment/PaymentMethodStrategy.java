package com.fashioncommerce.fashioncommercebackend.commerce.payment;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethod;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethodResponse;

import java.time.Instant;
import java.util.UUID;

public interface PaymentMethodStrategy {
    PaymentMethod method();

    PaymentMethodResponse availability(PaymentContext context);

    PaymentPreparation prepare(PaymentContext context);

    record PaymentContext(UUID buyerId, UUID checkoutSnapshotId, long amountMinor,
                          String currency, String returnUrl, Instant now) { }

    enum InventoryMode { COMMIT, RESERVE }

    record PaymentPreparation(
            String provider, String paymentStatus, String orderStatus, String shopOrderStatus,
            InventoryMode inventoryMode, Instant expiresAt, String providerPaymentId,
            String checkoutUrl) { }
}
