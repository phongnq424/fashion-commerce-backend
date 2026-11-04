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
    public SliceResponse<ShopProductSummaryResponse> products(@PathVariable UUID shopId,
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
                                   @PathVariable UUID productId, @RequestParam long expectedVersion) {
        return service.publish(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, expectedVersion);
    }

    @PostMapping("/{productId}/unpublish")
    public ProductResponse unpublish(Authentication identity, @PathVariable UUID shopId,
                                     @PathVariable UUID productId, @RequestParam long expectedVersion) {
        return service.unpublish(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, expectedVersion);
    }

    @PostMapping("/{productId}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createVariant(Authentication identity, @PathVariable UUID shopId,
                                         @PathVariable UUID productId,
                                         @Valid @RequestBody CreateVariantRequest request) {
        return service.createVariant(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, request);
    }

    @PatchMapping("/{productId}/variants/{variantId}")
    public ProductResponse updateVariant(Authentication identity, @PathVariable UUID shopId,
                                         @PathVariable UUID productId, @PathVariable UUID variantId,
                                         @Valid @RequestBody UpdateVariantRequest request) {
        return service.updateVariant(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, variantId, request);
    }

    @PutMapping("/{productId}/variants/{variantId}/price")
    public ProductResponse updateVariantPrice(Authentication identity, @PathVariable UUID shopId,
                                              @PathVariable UUID productId, @PathVariable UUID variantId,
                                              @Valid @RequestBody UpdateVariantPriceRequest request) {
        return service.updateVariantPrice(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, variantId, request);
    }

    @PutMapping("/{productId}/variants/{variantId}/status")
    public ProductResponse updateVariantStatus(Authentication identity, @PathVariable UUID shopId,
                                               @PathVariable UUID productId, @PathVariable UUID variantId,
                                               @Valid @RequestBody UpdateVariantStatusRequest request) {
        return service.updateVariantStatus(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, variantId, request);
    }

    @PostMapping("/{productId}/images")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse addImage(Authentication identity, @PathVariable UUID shopId,
                                    @PathVariable UUID productId, @Valid @RequestBody AddImageRequest request) {
        return service.addImage(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, request);
    }

    @DeleteMapping("/{productId}/images/{imageId}")
    public ProductResponse deleteImage(Authentication identity, @PathVariable UUID shopId,
                                       @PathVariable UUID productId, @PathVariable UUID imageId,
                                       @RequestParam long expectedVersion) {
        return service.deleteImage(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId,
                imageId, expectedVersion);
    }

    @PutMapping("/{productId}/images/order")
    public ProductResponse reorderImages(Authentication identity, @PathVariable UUID shopId,
                                         @PathVariable UUID productId,
                                         @Valid @RequestBody ReorderImagesRequest request) {
        return service.reorderImages(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId, request);
    }

    @PutMapping("/{productId}/images/{imageId}/primary")
    public ProductResponse setPrimaryImage(Authentication identity, @PathVariable UUID shopId,
                                           @PathVariable UUID productId, @PathVariable UUID imageId,
                                           @Valid @RequestBody SetPrimaryImageRequest request) {
        return service.setPrimaryImage(CurrentIdentity.userId(identity).orElseThrow(), shopId, productId,
                imageId, request);
    }

    @PutMapping("/variants/{variantId}/inventory")
    public ProductResponse inventory(Authentication identity, @PathVariable UUID shopId,
                                     @PathVariable UUID variantId,
                                     @Valid @RequestBody InventoryRequest request) {
        return service.setInventory(CurrentIdentity.userId(identity).orElseThrow(), shopId, variantId, request);
    }
}
