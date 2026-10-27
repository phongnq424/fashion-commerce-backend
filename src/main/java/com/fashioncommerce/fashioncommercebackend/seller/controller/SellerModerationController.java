package com.fashioncommerce.fashioncommercebackend.seller.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.service.SellerService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/seller-applications")
public class SellerModerationController {
    private final SellerService service;

    public SellerModerationController(SellerService service) {
        this.service = service;
    }

    @GetMapping
    public SliceResponse<ApplicationResponse> queue(@RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "20") int limit,
                                                    @RequestParam(defaultValue = "0") int offset) {
        return service.reviewQueue(status, limit, offset);
    }

    @GetMapping("/{applicationId}")
    public ApplicationResponse get(@PathVariable UUID applicationId) {
        return service.applicationForReview(applicationId);
    }

    @PostMapping("/{applicationId}/review/start")
    public ApplicationResponse start(Authentication identity, @PathVariable UUID applicationId) {
        return service.startReview(CurrentIdentity.userId(identity).orElseThrow(), applicationId);
    }

    @PostMapping("/{applicationId}/decision")
    public ApplicationResponse decide(Authentication identity, @PathVariable UUID applicationId,
                                      @Valid @RequestBody ReviewRequest request) {
        return service.decide(CurrentIdentity.userId(identity).orElseThrow(), applicationId, request);
    }
}
