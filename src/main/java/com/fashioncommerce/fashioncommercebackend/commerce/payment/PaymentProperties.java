package com.fashioncommerce.fashioncommercebackend.commerce.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("payments")
public record PaymentProperties(
        Duration onlineReservationTtl,
        Gateway momo,
        Gateway zalopay,
        Gateway vnpay) {

    public PaymentProperties {
        onlineReservationTtl = onlineReservationTtl == null ? Duration.ofMinutes(15) : onlineReservationTtl;
        momo = momo == null ? Gateway.disabled() : momo;
        zalopay = zalopay == null ? Gateway.disabled() : zalopay;
        vnpay = vnpay == null ? Gateway.disabled() : vnpay;
        if (onlineReservationTtl.isNegative() || onlineReservationTtl.isZero()) {
            throw new IllegalArgumentException("Online payment reservation TTL must be positive");
        }
    }

    public record Gateway(boolean enabled, String merchantId, String apiKey, String secretKey) {
        static Gateway disabled() { return new Gateway(false, null, null, null); }

        public boolean configured() {
            return enabled && present(merchantId) && present(apiKey) && present(secretKey);
        }

        private static boolean present(String value) { return value != null && !value.isBlank(); }
    }
}
