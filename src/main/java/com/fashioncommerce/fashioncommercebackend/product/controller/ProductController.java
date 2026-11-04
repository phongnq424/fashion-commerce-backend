package com.fashioncommerce.fashioncommercebackend.product.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.CursorPageResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.*;
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
    public CursorPageResponse<PublicProductSummaryResponse> products(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) Gender gender,
            @RequestParam(required = false) Long minPriceMinor,
            @RequestParam(required = false) Long maxPriceMinor,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(defaultValue = "NEWEST") ProductSort sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.publicProducts(new PublicProductQuery(query, categoryId, brandId, gender,
                minPriceMinor, maxPriceMinor, inStock, sort, cursor, limit));
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
