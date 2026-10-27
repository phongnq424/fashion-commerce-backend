package com.fashioncommerce.fashioncommercebackend.seller.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.service.SellerService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/seller/applications")
public class SellerController {
    private final SellerService service;

    public SellerController(SellerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> apply(Authentication identity, @Valid @RequestBody ApplyRequest request) {
        ApplicationResponse response = service.apply(CurrentIdentity.userId(identity).orElseThrow(), request);
        return ResponseEntity.created(URI.create("/api/v1/seller/applications/" + response.id())).body(response);
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

    @PutMapping("/{applicationId}")
    public ApplicationResponse update(Authentication identity, @PathVariable UUID applicationId,
                                      @Valid @RequestBody ApplyRequest request) {
        return service.update(CurrentIdentity.userId(identity).orElseThrow(), applicationId, request);
    }

    @DeleteMapping("/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(Authentication identity, @PathVariable UUID applicationId) {
        service.cancel(CurrentIdentity.userId(identity).orElseThrow(), applicationId);
    }
}

