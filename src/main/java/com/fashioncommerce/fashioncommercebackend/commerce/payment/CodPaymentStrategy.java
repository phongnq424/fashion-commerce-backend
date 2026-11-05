package com.fashioncommerce.fashioncommercebackend.commerce.payment;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethod;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethodResponse;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethodStatus;
import org.springframework.stereotype.Component;

@Component
public class CodPaymentStrategy implements PaymentMethodStrategy {
    @Override public PaymentMethod method() { return PaymentMethod.COD; }

    @Override
    public PaymentMethodResponse availability(PaymentContext context) {
        return new PaymentMethodResponse(method(), "Thanh toán khi nhận hàng", "COD",
                true, true, PaymentMethodStatus.AVAILABLE, null);
    }

    @Override
    public PaymentPreparation prepare(PaymentContext context) {
        return new PaymentPreparation("COD", "AWAITING_COLLECTION", "CONFIRMED",
                "PENDING_CONFIRMATION", InventoryMode.COMMIT, null, null, null);
    }
}
