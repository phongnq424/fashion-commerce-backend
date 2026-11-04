package com.fashioncommerce.fashioncommercebackend.auth.email;

import java.time.Duration;

public interface RegistrationEmailSender {
    void sendRegistrationOtp(String email, String code, Duration ttl);
    void sendPasswordResetOtp(String email, String code, Duration ttl);
}
