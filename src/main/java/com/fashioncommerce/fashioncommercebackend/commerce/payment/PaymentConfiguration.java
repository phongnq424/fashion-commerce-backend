package com.fashioncommerce.fashioncommercebackend.commerce.payment;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
class PaymentConfiguration {
    @Bean PaymentMethodStrategy momoPaymentStrategy(PaymentProperties properties) {
        return new DeferredGatewayPaymentStrategy(PaymentMethod.MOMO, "Ví MoMo", properties.momo());
    }

    @Bean PaymentMethodStrategy zalopayPaymentStrategy(PaymentProperties properties) {
        return new DeferredGatewayPaymentStrategy(PaymentMethod.ZALOPAY, "Ví ZaloPay", properties.zalopay());
    }

    @Bean PaymentMethodStrategy vnpayPaymentStrategy(PaymentProperties properties) {
        return new DeferredGatewayPaymentStrategy(PaymentMethod.VNPAY, "VNPay", properties.vnpay());
    }
}
