package com.fashioncommerce.fashioncommercebackend.commerce.controller;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.CheckoutPreviewRequest;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.CheckoutPreviewResponse;
import com.fashioncommerce.fashioncommercebackend.commerce.service.CommerceService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {
    private final CommerceService service;

    public CheckoutController(CommerceService service) { this.service = service; }

    @PostMapping("/preview")
    public CheckoutPreviewResponse preview(Authentication identity,
                                            @Valid @RequestBody CheckoutPreviewRequest request) {
        return service.checkoutPreview(CurrentIdentity.userId(identity).orElseThrow(), request);
    }
}
