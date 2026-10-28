package com.fashioncommerce.fashioncommercebackend.product.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.ModerationCaseResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.PublicProductResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.ReportProductRequest;
import com.fashioncommerce.fashioncommercebackend.product.service.ProductService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public SliceResponse<PublicProductResponse> products(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.publicProducts(categoryId, limit, offset);
    }

    @GetMapping("/{productId}")
    public PublicProductResponse product(@PathVariable UUID productId) {
        return service.publicProduct(productId);
    }

    @PostMapping("/{productId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ModerationCaseResponse report(Authentication identity, @PathVariable UUID productId,
                                         @Valid @RequestBody ReportProductRequest request) {
        return service.report(CurrentIdentity.userId(identity).orElseThrow(), productId, request);
    }
}
