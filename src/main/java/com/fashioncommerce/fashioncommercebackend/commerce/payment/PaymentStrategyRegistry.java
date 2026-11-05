package com.fashioncommerce.fashioncommercebackend.commerce.payment;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethod;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentStrategyRegistry {
    private final Map<PaymentMethod, PaymentMethodStrategy> strategies;

    public PaymentStrategyRegistry(List<PaymentMethodStrategy> strategies) {
        EnumMap<PaymentMethod, PaymentMethodStrategy> indexed = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethodStrategy strategy : strategies) {
            if (indexed.put(strategy.method(), strategy) != null) {
                throw new IllegalStateException("Duplicate payment strategy: " + strategy.method());
            }
        }
        for (PaymentMethod method : PaymentMethod.values()) {
            if (!indexed.containsKey(method)) throw new IllegalStateException("Missing payment strategy: " + method);
        }
        this.strategies = Map.copyOf(indexed);
    }

    public PaymentMethodStrategy required(PaymentMethod method) {
        PaymentMethodStrategy strategy = strategies.get(method);
        if (strategy == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "PAYMENT_METHOD_INVALID", "Unsupported payment method");
        return strategy;
    }

    public List<PaymentMethodStrategy> all() {
        return java.util.Arrays.stream(PaymentMethod.values()).map(this::required).toList();
    }
}
