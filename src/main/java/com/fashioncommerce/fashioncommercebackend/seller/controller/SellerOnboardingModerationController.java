package com.fashioncommerce.fashioncommercebackend.seller.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerOnboardingDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.service.SellerOnboardingService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/seller-onboardings")
public class SellerOnboardingModerationController {
    private final SellerOnboardingService service;

    public SellerOnboardingModerationController(SellerOnboardingService service) { this.service = service; }

    @GetMapping("/{onboardingId}")
    public OnboardingResponse get(@PathVariable UUID onboardingId) {
        return service.moderationGet(onboardingId);
    }

    @GetMapping("/reviews")
    public SliceResponse<OnboardingResponse> queue(
            @RequestParam(defaultValue = "PENDING_REVIEW") String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.reviewQueue(status, limit, offset);
    }

    @PostMapping("/{onboardingId}/decision")
    public OnboardingResponse decide(Authentication identity, @PathVariable UUID onboardingId,
                                     @Valid @RequestBody ReviewRequest request) {
        return service.decide(CurrentIdentity.userId(identity).orElseThrow(), onboardingId, request);
    }
}
