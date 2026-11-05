package com.fashioncommerce.fashioncommercebackend.commerce.payment;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethod;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethodResponse;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethodStatus;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.springframework.http.HttpStatus;

final class DeferredGatewayPaymentStrategy implements PaymentMethodStrategy {
    private final PaymentMethod method;
    private final String displayName;
    private final PaymentProperties.Gateway configuration;

    DeferredGatewayPaymentStrategy(PaymentMethod method, String displayName,
                                   PaymentProperties.Gateway configuration) {
        this.method = method;
        this.displayName = displayName;
        this.configuration = configuration;
    }

    @Override public PaymentMethod method() { return method; }

    @Override
    public PaymentMethodResponse availability(PaymentContext context) {
        boolean configured = configuration.configured();
        PaymentMethodStatus status = configured
                ? PaymentMethodStatus.NOT_IMPLEMENTED : PaymentMethodStatus.NOT_CONFIGURED;
        String reason = configured
                ? "Gateway integration is not implemented yet"
                : "Gateway credentials are not configured";
        return new PaymentMethodResponse(method, displayName, method.name(), configured, false, status, reason);
    }

    @Override
    public PaymentPreparation prepare(PaymentContext context) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "PAYMENT_METHOD_UNAVAILABLE",
                method + " is not available");
    }
}
