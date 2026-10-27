package com.fashioncommerce.fashioncommercebackend.seller.dto;

import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SellerShopRequestValidationTests {
    @Test void validatesSellerAndNestedPickupAddress() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var seller = new SellerDtos.ApplyRequest("My Shop", "seller@example.com", "+84901234567",
                    "Seller Name", "1234", null);
            var address = new PickupAddress("Seller", "+84901234567", "1 Main Street", null,
                    "Ward 1", "District 1", "Ho Chi Minh City", "79", null, "VN");
            var shop = new CreateRequest(UUID.randomUUID(), "My Shop", "my-shop", null, null, null,
                    "shop@example.com", "+84901234567", address);

            assertThat(validator.validate(seller)).isEmpty();
            assertThat(validator.validate(shop)).isEmpty();
            assertThat(validator.validate(new CreateRequest(UUID.randomUUID(), "My Shop", "Invalid Slug", null,
                    null, null, "wrong", "123", address))).isNotEmpty();
        }
    }
}
