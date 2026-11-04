package com.fashioncommerce.fashioncommercebackend.commerce.controller;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.service.CommerceService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
public class CartController {
    private final CommerceService service;

    public CartController(CommerceService service) { this.service = service; }

    @GetMapping
    public CartResponse cart(Authentication identity) {
        return service.cart(CurrentIdentity.userId(identity).orElseThrow());
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public CartResponse addItem(Authentication identity, @Valid @RequestBody AddCartItemRequest request) {
        return service.addItem(CurrentIdentity.userId(identity).orElseThrow(), request);
    }

    @PutMapping("/items/{itemId}")
    public CartResponse updateItem(Authentication identity, @PathVariable UUID itemId,
                                   @Valid @RequestBody UpdateCartItemRequest request) {
        return service.updateItem(CurrentIdentity.userId(identity).orElseThrow(), itemId, request);
    }

    @DeleteMapping("/items/{itemId}")
    public CartResponse deleteItem(Authentication identity, @PathVariable UUID itemId,
                                   @RequestParam long expectedVersion) {
        return service.deleteItem(CurrentIdentity.userId(identity).orElseThrow(), itemId, expectedVersion);
    }

    @PutMapping("/selection")
    public CartResponse updateSelection(Authentication identity,
                                        @Valid @RequestBody UpdateCartSelectionRequest request) {
        return service.updateSelection(CurrentIdentity.userId(identity).orElseThrow(), request);
    }
}
