package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequestValidationTests {
    @Test void validatesPasswordAndPhoneWithoutChangingPassword() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "0901234567", "StrongPassword123", "Name", "123456"))).isEmpty();
            assertThat(validator.validate(new RegisterRequest(null, "0901234567", "StrongPassword123", "Name", "123456"))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "+84901234567", "short1", "Name", "123456"))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "84901234567", "longwithoutdigits", "Name", "123456"))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "0901234567", "MậtKhẩu1".repeat(9), "Name", "123456"))).isNotEmpty();
            assertThat(validator.validate(new RegisterRequest("a@example.com", "", "StrongPassword123", "Name", "123"))).isNotEmpty();
        }
    }
}
