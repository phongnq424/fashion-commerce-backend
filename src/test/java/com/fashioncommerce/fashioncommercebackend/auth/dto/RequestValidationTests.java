package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestValidationTests {
    @Test void validatesPasswordAndPhoneWithoutChangingPassword() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "StrongPassword123", "Name", "+84901234567"))).isEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "short1", "Name", null))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "longwithoutdigits", "Name", null))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "MậtKhẩu1".repeat(9), "Name", null))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "StrongPassword123", "Name", ""))).isNotEmpty();
        }
    }
}
