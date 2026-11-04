package com.fashioncommerce.fashioncommercebackend.auth.service;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhoneNumberNormalizerTests {
    @Test void normalizesSupportedVietnameseFormatsToE164() {
        assertThat(PhoneNumberNormalizer.normalizeVietnameseMobile("0901234567")).isEqualTo("+84901234567");
        assertThat(PhoneNumberNormalizer.normalizeVietnameseMobile("84901234567")).isEqualTo("+84901234567");
        assertThat(PhoneNumberNormalizer.normalizeVietnameseMobile("+84901234567")).isEqualTo("+84901234567");
    }

    @Test void rejectsLandlineForeignAndMalformedNumbers() {
        assertThatThrownBy(() -> PhoneNumberNormalizer.normalizeVietnameseMobile("0241234567")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PhoneNumberNormalizer.normalizeVietnameseMobile("+12025550123")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PhoneNumberNormalizer.normalizeVietnameseMobile("090 123 4567")).isInstanceOf(ApiException.class);
    }
}
