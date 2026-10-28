package com.fashioncommerce.fashioncommercebackend.product.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.ModerateProductRequest;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.ModerationCaseResponse;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.ProductResponse;
import com.fashioncommerce.fashioncommercebackend.product.service.ProductService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/product-moderation")
public class ProductModerationController {
    private final ProductService service;

    public ProductModerationController(ProductService service) {
        this.service = service;
    }

    @GetMapping("/cases")
    public SliceResponse<ModerationCaseResponse> cases(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.cases(status, limit, offset);
    }

    @GetMapping("/cases/{caseId}")
    public ModerationCaseResponse moderationCase(@PathVariable UUID caseId) {
        return service.moderationCase(caseId);
    }

    @PostMapping("/products/{productId}/decision")
    public ProductResponse decide(Authentication identity, @PathVariable UUID productId,
                                  @Valid @RequestBody ModerateProductRequest request) {
        return service.moderate(CurrentIdentity.userId(identity).orElseThrow(), productId, request);
    }
}
