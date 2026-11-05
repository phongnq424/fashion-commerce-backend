package com.fashioncommerce.fashioncommercebackend.commerce.controller;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.PaymentResponse;
import com.fashioncommerce.fashioncommercebackend.commerce.service.OrderService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final OrderService service;

    public PaymentController(OrderService service) { this.service = service; }

    @GetMapping("/{paymentId}")
    public PaymentResponse payment(Authentication identity, @PathVariable UUID paymentId) {
        return service.payment(CurrentIdentity.userId(identity).orElseThrow(), paymentId);
    }
}
