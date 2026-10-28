package com.fashioncommerce.fashioncommercebackend.seller.controller;

import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerOnboardingDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.service.SellerOnboardingService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/seller/onboarding")
public class SellerOnboardingController {
    private final SellerOnboardingService service;

    public SellerOnboardingController(SellerOnboardingService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<OnboardingResponse> start(Authentication identity,
                                                    @Valid @RequestBody ShopProfileRequest request) {
        OnboardingResponse response = service.start(userId(identity), request);
        return ResponseEntity.created(URI.create("/api/v1/seller/onboarding/" + response.id())).body(response);
    }

    @GetMapping("/me")
    public OnboardingResponse mine(Authentication identity) { return service.mine(userId(identity)); }

    @GetMapping("/{onboardingId}")
    public OnboardingResponse get(Authentication identity, @PathVariable UUID onboardingId) {
        return service.getOwn(userId(identity), onboardingId);
    }

    @PutMapping("/{onboardingId}/shop")
    public OnboardingResponse updateShop(Authentication identity, @PathVariable UUID onboardingId,
                                         @Valid @RequestBody ShopProfileRequest request) {
        return service.updateShop(userId(identity), onboardingId, request);
    }

    @PutMapping("/{onboardingId}/pickup-address")
    public PickupAddressResponse savePickup(Authentication identity, @PathVariable UUID onboardingId,
                                            @Valid @RequestBody PickupAddressRequest request) {
        return service.savePickup(userId(identity), onboardingId, request);
    }

    @GetMapping("/{onboardingId}/shipping-providers")
    public List<ShippingProviderResponse> shippingProviders(Authentication identity,
                                                            @PathVariable UUID onboardingId) {
        return service.shippingProviders(userId(identity), onboardingId);
    }

    @PutMapping("/{onboardingId}/shipping")
    public List<ShippingProviderResponse> configureShipping(Authentication identity,
                                                            @PathVariable UUID onboardingId,
                                                            @Valid @RequestBody ShippingConfigurationRequest request) {
        return service.configureShipping(userId(identity), onboardingId, request);
    }

    @PutMapping("/{onboardingId}/tax")
    public TaxProfileResponse saveTax(Authentication identity, @PathVariable UUID onboardingId,
                                      @Valid @RequestBody TaxProfileRequest request) {
        return service.saveTax(userId(identity), onboardingId, request);
    }

    @PutMapping("/{onboardingId}/identity")
    public OnboardingResponse submitIdentity(Authentication identity, @PathVariable UUID onboardingId,
                                             @Valid @RequestBody IdentityVerificationRequest request) {
        return service.submitIdentity(userId(identity), onboardingId, request);
    }

    @PostMapping("/{onboardingId}/policy-acceptances")
    public OnboardingResponse acceptPolicy(Authentication identity, @PathVariable UUID onboardingId,
                                           @Valid @RequestBody PolicyAcceptanceRequest request) {
        return service.acceptPolicy(userId(identity), onboardingId, request);
    }

    @GetMapping("/{onboardingId}/policies")
    public List<PolicyVersionResponse> policies(Authentication identity, @PathVariable UUID onboardingId) {
        return service.policies(userId(identity), onboardingId);
    }

    @PostMapping("/{onboardingId}/complete")
    public OnboardingResponse complete(Authentication identity, @PathVariable UUID onboardingId) {
        return service.complete(userId(identity), onboardingId);
    }

    private static UUID userId(Authentication identity) {
        return CurrentIdentity.userId(identity).orElseThrow();
    }
}
