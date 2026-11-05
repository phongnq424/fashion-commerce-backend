package com.fashioncommerce.fashioncommercebackend.commerce.payment;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethod;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethodStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStrategyTests {
    private final PaymentMethodStrategy.PaymentContext context = new PaymentMethodStrategy.PaymentContext(
            UUID.randomUUID(), UUID.randomUUID(), 250_000, "VND", null, Instant.now());

    @Test
    void codIsExecutableAndCommitsInventory() {
        CodPaymentStrategy strategy = new CodPaymentStrategy();

        assertThat(strategy.availability(context).available()).isTrue();
        assertThat(strategy.prepare(context).inventoryMode())
                .isEqualTo(PaymentMethodStrategy.InventoryMode.COMMIT);
        assertThat(strategy.prepare(context).paymentStatus()).isEqualTo("AWAITING_COLLECTION");
    }

    @Test
    void configuredGatewayStaysUnavailableUntilClientIsImplemented() {
        var configuration = new PaymentProperties.Gateway(true, "merchant", "api-key", "secret");
        var strategy = new DeferredGatewayPaymentStrategy(PaymentMethod.MOMO, "Ví MoMo", configuration);

        assertThat(strategy.availability(context).configured()).isTrue();
        assertThat(strategy.availability(context).available()).isFalse();
        assertThat(strategy.availability(context).status()).isEqualTo(PaymentMethodStatus.NOT_IMPLEMENTED);
    }

    @Test
    void registryKeepsStablePaymentMethodOrder() {
        PaymentProperties.Gateway disabled = new PaymentProperties.Gateway(false, null, null, null);
        PaymentStrategyRegistry registry = new PaymentStrategyRegistry(List.of(
                new DeferredGatewayPaymentStrategy(PaymentMethod.VNPAY, "VNPay", disabled),
                new CodPaymentStrategy(),
                new DeferredGatewayPaymentStrategy(PaymentMethod.ZALOPAY, "ZaloPay", disabled),
                new DeferredGatewayPaymentStrategy(PaymentMethod.MOMO, "MoMo", disabled)));

        assertThat(registry.all()).extracting(PaymentMethodStrategy::method)
                .containsExactly(PaymentMethod.COD, PaymentMethod.MOMO, PaymentMethod.ZALOPAY, PaymentMethod.VNPAY);
    }
}
