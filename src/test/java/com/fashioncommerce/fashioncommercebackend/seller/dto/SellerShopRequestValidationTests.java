package com.fashioncommerce.fashioncommercebackend.seller.dto;

import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SellerShopRequestValidationTests {
    @Test void validatesNestedShopPickupAddress() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var address = new PickupAddress("Seller", "+84901234567", "1 Main Street", null,
                    "Ward 1", "District 1", "Ho Chi Minh City", "79", null, "VN");
            var shop = new UpdateRequest("My Shop", "my-shop", null, null, null,
                    "shop@example.com", "+84901234567", address);
            var invalidAddress = new PickupAddress("Seller", "123", "1 Main Street", null,
                    "Ward 1", "District 1", "Ho Chi Minh City", "79", null, "VN");

            assertThat(validator.validate(shop)).isEmpty();
            assertThat(validator.validate(new UpdateRequest("My Shop", "Invalid Slug", null,
                    null, null, "wrong", "123", invalidAddress))).isNotEmpty();
        }
    }
}
