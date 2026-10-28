package com.fashioncommerce.fashioncommercebackend.product.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.*;
import com.fashioncommerce.fashioncommercebackend.product.service.ProductService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/products")
public class ShopProductController {
    private final ProductService service;

    public ShopProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(Authentication identity, @PathVariable UUID shopId,
                                  @Valid @RequestBody CreateProductRequest request) {
        return service.create(CurrentIdentity.userId(identity).orElseThrow(), shopId, request);
    }

    @GetMapping
    public SliceResponse<ProductResponse> products(@PathVariable UUID shopId,
                                                   @RequestParam(defaultValue = "20") int limit,
                                                   @RequestParam(defaultValue = "0") int offset) {
        return service.shopProducts(shopId, limit, offset);
    }

    @GetMapping("/{productId}")
    public ProductResponse product(@PathVariable UUID shopId, @PathVariable UUID productId) {
        return service.shopProduct(shopId, productId);
    }

    @PatchMapping("/{productId}")
    public ProductResponse update(Authentication identity, @PathVariable UUID shopId,
                                  @PathVariable UUID productId,
                                  @Valid @RequestBody UpdateProductRequest request) {
        return service.update(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, request);
    }

    @PostMapping("/{productId}/publish")
    public ProductResponse publish(Authentication identity, @PathVariable UUID shopId,
                                   @PathVariable UUID productId) {
        return service.publish(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId);
    }

    @PostMapping("/{productId}/unpublish")
    public ProductResponse unpublish(Authentication identity, @PathVariable UUID shopId,
                                     @PathVariable UUID productId) {
        return service.unpublish(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId);
    }

    @PutMapping("/variants/{variantId}/inventory")
    public ProductResponse inventory(Authentication identity, @PathVariable UUID shopId,
                                     @PathVariable UUID variantId,
                                     @Valid @RequestBody InventoryRequest request) {
        return service.setInventory(CurrentIdentity.userId(identity).orElseThrow(), shopId, variantId, request);
    }
}
