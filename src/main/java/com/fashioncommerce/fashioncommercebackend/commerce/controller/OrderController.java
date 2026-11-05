package com.fashioncommerce.fashioncommercebackend.commerce.controller;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.service.OrderService;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService service;

    public OrderController(OrderService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse place(Authentication identity,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @Valid @RequestBody PlaceOrderRequest request) {
        return service.place(userId(identity), idempotencyKey, request);
    }

    @GetMapping
    public SliceResponse<OrderSummaryResponse> orders(Authentication identity,
                                                       @RequestParam(defaultValue = "20") int limit,
                                                       @RequestParam(defaultValue = "0") int offset) {
        return service.orders(userId(identity), limit, offset);
    }

    @GetMapping("/{orderId}")
    public OrderResponse order(Authentication identity, @PathVariable UUID orderId) {
        return service.order(userId(identity), orderId);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(Authentication identity, @PathVariable UUID orderId,
                                @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return service.cancel(userId(identity), orderId, request);
    }

    private static UUID userId(Authentication identity) {
        return CurrentIdentity.userId(identity).orElseThrow();
    }
}
