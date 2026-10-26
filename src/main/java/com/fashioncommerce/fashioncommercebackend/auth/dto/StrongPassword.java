package com.fashioncommerce.fashioncommercebackend.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.lang.annotation.*;
import java.nio.charset.StandardCharsets;

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StrongPassword.Validator.class)
public @interface StrongPassword {
    String message() default "Password requires at least 12 characters, a letter and a digit, and at most 72 UTF-8 bytes";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<StrongPassword, String> {
        @Override public boolean isValid(String value, ConstraintValidatorContext context) {
            return value != null && value.codePointCount(0, value.length()) >= 12
                    && value.getBytes(StandardCharsets.UTF_8).length <= 72
                    && value.codePoints().anyMatch(Character::isLetter)
                    && value.codePoints().anyMatch(Character::isDigit);
        }
    }
}
