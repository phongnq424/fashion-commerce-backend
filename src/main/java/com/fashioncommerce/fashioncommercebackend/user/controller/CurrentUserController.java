package com.fashioncommerce.fashioncommercebackend.user.controller;

import com.fashioncommerce.fashioncommercebackend.auth.dto.UserResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.user.service.CurrentUserService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class CurrentUserController {
    private final CurrentUserService service;
    public CurrentUserController(CurrentUserService service) { this.service = service; }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(Authentication identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.currentUser(CurrentIdentity.userId(identity).orElseThrow()));
    }
}
