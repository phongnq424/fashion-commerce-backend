package com.fashioncommerce.fashioncommercebackend.shop.controller;

import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import com.fashioncommerce.fashioncommercebackend.shop.service.ShopService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shop-invitations")
public class ShopInvitationController {
    private final ShopService service;

    public ShopInvitationController(ShopService service) {
        this.service = service;
    }

    @GetMapping
    public List<InvitationResponse> invitations(Authentication identity) {
        return service.invitations(CurrentIdentity.userId(identity).orElseThrow());
    }

    @PostMapping("/{memberId}/accept")
    public MemberResponse accept(Authentication identity, @PathVariable UUID memberId) {
        return service.acceptInvitation(CurrentIdentity.userId(identity).orElseThrow(), memberId);
    }

    @PostMapping("/{memberId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(Authentication identity, @PathVariable UUID memberId) {
        service.declineInvitation(CurrentIdentity.userId(identity).orElseThrow(), memberId);
    }
}

