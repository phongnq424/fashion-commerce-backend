package com.fashioncommerce.fashioncommercebackend.seller.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.service.SellerService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/seller/applications")
public class SellerController {
    private final SellerService service;

    public SellerController(SellerService service) {
        this.service = service;
    }

    @GetMapping
    public SliceResponse<ApplicationResponse> mine(Authentication identity,
                                                   @RequestParam(defaultValue = "20") int limit,
                                                   @RequestParam(defaultValue = "0") int offset) {
        return service.ownApplications(CurrentIdentity.userId(identity).orElseThrow(), limit, offset);
    }

    @GetMapping("/{applicationId}")
    public ApplicationResponse get(Authentication identity, @PathVariable UUID applicationId) {
        return service.ownApplication(CurrentIdentity.userId(identity).orElseThrow(), applicationId);
    }

}

