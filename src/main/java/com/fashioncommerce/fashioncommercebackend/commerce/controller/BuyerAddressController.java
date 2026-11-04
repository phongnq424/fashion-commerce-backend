package com.fashioncommerce.fashioncommercebackend.commerce.controller;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.service.CommerceService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/addresses")
public class BuyerAddressController {
    private final CommerceService service;

    public BuyerAddressController(CommerceService service) { this.service = service; }

    @GetMapping
    public List<AddressResponse> addresses(Authentication identity) {
        return service.addresses(CurrentIdentity.userId(identity).orElseThrow());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(Authentication identity, @Valid @RequestBody CreateAddressRequest request) {
        return service.createAddress(CurrentIdentity.userId(identity).orElseThrow(), request);
    }

    @PutMapping("/{addressId}")
    public AddressResponse update(Authentication identity, @PathVariable UUID addressId,
                                  @Valid @RequestBody UpdateAddressRequest request) {
        return service.updateAddress(CurrentIdentity.userId(identity).orElseThrow(), addressId, request);
    }

    @DeleteMapping("/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication identity, @PathVariable UUID addressId) {
        service.deleteAddress(CurrentIdentity.userId(identity).orElseThrow(), addressId);
    }
}
