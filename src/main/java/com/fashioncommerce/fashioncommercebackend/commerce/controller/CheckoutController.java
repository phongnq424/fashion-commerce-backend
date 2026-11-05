package com.fashioncommerce.fashioncommercebackend.commerce.controller;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.CheckoutPreviewRequest;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.CheckoutPreviewResponse;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentMethodResponse;
import com.fashioncommerce.fashioncommercebackend.commerce.service.CommerceService;
import com.fashioncommerce.fashioncommercebackend.commerce.service.OrderService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/checkout")
public class CheckoutController {
    private final CommerceService service;
    private final OrderService orders;

    public CheckoutController(CommerceService service, OrderService orders) {
        this.service = service;
        this.orders = orders;
    }

    @PostMapping("/preview")
    public CheckoutPreviewResponse preview(Authentication identity,
                                            @Valid @RequestBody CheckoutPreviewRequest request) {
        return service.checkoutPreview(CurrentIdentity.userId(identity).orElseThrow(), request);
    }

    @GetMapping("/{snapshotId}/payment-methods")
    public List<PaymentMethodResponse> paymentMethods(Authentication identity, @PathVariable UUID snapshotId) {
        return orders.paymentMethods(CurrentIdentity.userId(identity).orElseThrow(), snapshotId);
    }
}
