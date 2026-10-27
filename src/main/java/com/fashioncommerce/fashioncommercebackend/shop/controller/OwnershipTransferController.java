package com.fashioncommerce.fashioncommercebackend.shop.controller;

import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.OwnershipTransferResponse;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.ShopResponse;
import com.fashioncommerce.fashioncommercebackend.shop.service.ShopService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shop-ownership-transfers")
public class OwnershipTransferController {
    private final ShopService service;

    public OwnershipTransferController(ShopService service) {
        this.service = service;
    }

    @GetMapping
    public List<OwnershipTransferResponse> received(Authentication identity) {
        return service.ownershipTransferRequests(CurrentIdentity.userId(identity).orElseThrow());
    }

    @PostMapping("/{transferId}/accept")
    public ShopResponse accept(Authentication identity, @PathVariable UUID transferId) {
        return service.acceptOwnershipTransfer(CurrentIdentity.userId(identity).orElseThrow(), transferId);
    }

    @PostMapping("/{transferId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(Authentication identity, @PathVariable UUID transferId) {
        service.rejectOwnershipTransfer(CurrentIdentity.userId(identity).orElseThrow(), transferId);
    }

    @DeleteMapping("/{transferId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(Authentication identity, @PathVariable UUID transferId) {
        service.cancelOwnershipTransfer(CurrentIdentity.userId(identity).orElseThrow(), transferId);
    }
}
