package com.fashioncommerce.fashioncommercebackend.auth.controller;

import com.fashioncommerce.fashioncommercebackend.auth.dto.*;
import com.fashioncommerce.fashioncommercebackend.auth.service.AuthenticationService;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
    private final AuthenticationService service;
    public AuthenticationController(AuthenticationService service) { this.service = service; }

    @PostMapping("/register/otp")
    public ResponseEntity<OtpChallengeResponse> requestRegistrationOtp(@Valid @RequestBody RegistrationOtpRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.requestRegistrationOtp(request));
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.register(request));
    }

    @PostMapping("/password-reset/otp")
    public ResponseEntity<OtpChallengeResponse> requestPasswordResetOtp(
            @Valid @RequestBody PasswordResetOtpRequest request) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(service.requestPasswordResetOtp(request));
    }

    @PostMapping("/password-reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody PasswordResetRequest request) {
        service.resetPassword(request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.refresh(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(Authentication identity, @Valid @RequestBody RefreshRequest request) {
        var accessToken = CurrentIdentity.accessToken(identity).orElseThrow();
        service.logout(accessToken.userId(), accessToken.tokenId(), accessToken.expiresAt(), request);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
