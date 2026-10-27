package com.fashioncommerce.fashioncommercebackend.shop.controller;

import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import com.fashioncommerce.fashioncommercebackend.shop.service.ShopService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shops")
public class ShopController {
    private final ShopService service;

    public ShopController(ShopService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ShopResponse> create(Authentication identity, @Valid @RequestBody CreateRequest request) {
        ShopResponse response = service.create(CurrentIdentity.userId(identity).orElseThrow(), request);
        return ResponseEntity.created(URI.create("/api/v1/shops/" + response.id())).body(response);
    }

    @GetMapping("/mine")
    public SliceResponse<ShopResponse> mine(Authentication identity,
                                            @RequestParam(defaultValue = "20") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return service.mine(CurrentIdentity.userId(identity).orElseThrow(), limit, offset);
    }

    @GetMapping("/{shopId}")
    public ShopResponse get(@PathVariable UUID shopId) {
        return service.get(shopId);
    }

    @PatchMapping("/{shopId}")
    public ShopResponse update(Authentication identity, @PathVariable UUID shopId,
                               @Valid @RequestBody UpdateRequest request) {
        return service.update(CurrentIdentity.userId(identity).orElseThrow(), shopId, request);
    }

    @DeleteMapping("/{shopId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(Authentication identity, @PathVariable UUID shopId) {
        service.closeByOwner(CurrentIdentity.userId(identity).orElseThrow(), shopId);
    }

    @GetMapping("/{shopId}/settings")
    public SettingsResponse settings(@PathVariable UUID shopId) {
        return service.settings(shopId);
    }

    @PutMapping("/{shopId}/settings")
    public SettingsResponse updateSettings(Authentication identity, @PathVariable UUID shopId,
                                           @Valid @RequestBody UpdateSettingsRequest request) {
        return service.updateSettings(CurrentIdentity.userId(identity).orElseThrow(), shopId, request);
    }

    @GetMapping("/{shopId}/members")
    public SliceResponse<MemberResponse> members(@PathVariable UUID shopId,
                                                 @RequestParam(defaultValue = "20") int limit,
                                                 @RequestParam(defaultValue = "0") int offset) {
        return service.members(shopId, limit, offset);
    }

    @GetMapping("/{shopId}/assignable-roles")
    public java.util.List<RoleResponse> assignableRoles(@PathVariable UUID shopId) {
        return service.assignableRoles(shopId);
    }

    @PostMapping("/{shopId}/members/invitations")
    public ResponseEntity<MemberResponse> invite(Authentication identity, @PathVariable UUID shopId,
                                                 @Valid @RequestBody InviteMemberRequest request) {
        MemberResponse response = service.invite(CurrentIdentity.userId(identity).orElseThrow(), shopId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{shopId}/members/{memberId}/roles")
    public MemberResponse updateRoles(Authentication identity, @PathVariable UUID shopId, @PathVariable UUID memberId,
                                      @Valid @RequestBody UpdateMemberRolesRequest request) {
        return service.updateMemberRoles(CurrentIdentity.userId(identity).orElseThrow(), shopId, memberId, request);
    }

    @DeleteMapping("/{shopId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(Authentication identity, @PathVariable UUID shopId, @PathVariable UUID memberId) {
        service.removeMember(CurrentIdentity.userId(identity).orElseThrow(), shopId, memberId);
    }

    @DeleteMapping("/{shopId}/members/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(Authentication identity, @PathVariable UUID shopId) {
        service.leaveShop(CurrentIdentity.userId(identity).orElseThrow(), shopId);
    }

    @PostMapping("/{shopId}/ownership-transfers")
    public ResponseEntity<OwnershipTransferResponse> transfer(Authentication identity, @PathVariable UUID shopId,
                                                              @Valid @RequestBody TransferOwnershipRequest request) {
        OwnershipTransferResponse response = service.requestOwnershipTransfer(
                CurrentIdentity.userId(identity).orElseThrow(), shopId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shopId}/ownership-transfers")
    public java.util.List<OwnershipTransferResponse> ownershipTransfers(@PathVariable UUID shopId) {
        return service.shopOwnershipTransfers(shopId);
    }
}


