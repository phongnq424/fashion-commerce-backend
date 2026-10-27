package com.fashioncommerce.fashioncommercebackend.shop.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import com.fashioncommerce.fashioncommercebackend.shop.service.ShopService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/shops")
public class ShopModerationController {
    private final ShopService service;

    public ShopModerationController(ShopService service) {
        this.service = service;
    }

    @GetMapping
    public SliceResponse<ShopResponse> shops(@RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "20") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return service.moderationQueue(status, limit, offset);
    }

    @GetMapping("/{shopId}")
    public ShopResponse get(@PathVariable UUID shopId) {
        return service.shopForModeration(shopId);
    }

    @PostMapping("/{shopId}/moderation")
    public ShopResponse moderate(Authentication identity, @PathVariable UUID shopId,
                                 @Valid @RequestBody ModerateShopRequest request) {
        return service.moderate(CurrentIdentity.userId(identity).orElseThrow(), shopId, request);
    }
}
