package com.fashioncommerce.fashioncommercebackend.auth.service;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.regex.Pattern;

public final class PhoneNumberNormalizer {
    private static final Pattern VIETNAM_MOBILE = Pattern.compile("^(?:\\+84|84|0)([35789][0-9]{8})$");

    private PhoneNumberNormalizer() { }

    public static String normalizeVietnameseMobile(String input) {
        String value = input == null ? "" : input.strip();
        var matcher = VIETNAM_MOBILE.matcher(value);
        if (!matcher.matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AUTH_PHONE_INVALID", "Invalid Vietnamese mobile number");
        }
        return "+84" + matcher.group(1);
    }
}
