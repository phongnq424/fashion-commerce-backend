package com.fashioncommerce.fashioncommercebackend.shop.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.audit.ModerationActionRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.ApplicationResponse;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerRepository;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import com.fashioncommerce.fashioncommercebackend.shop.repository.ShopRepository;
import com.fashioncommerce.fashioncommercebackend.shop.repository.ShopRepository.MemberRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ShopServiceTests {
    private final ShopRepository shops = mock(ShopRepository.class);
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final ModerationActionRepository moderation = mock(ModerationActionRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private ShopService service;

    @BeforeEach void setUp() {
        reset(shops, sellers, audit, moderation, notifications);
        service = new ShopService(shops, sellers, audit, moderation, notifications, clock);
    }

    @Test void transferRequiresRecipientConsent() {
        UUID shopId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID targetMemberId = UUID.randomUUID();
        ShopResponse shop = shop(shopId, ownerId);
        MemberRow target = new MemberRow(targetMemberId, shopId, targetId, ownerId, "ACTIVE", Instant.now(clock), Instant.now(clock));
        OwnershipTransferResponse transfer = new OwnershipTransferResponse(UUID.randomUUID(), shopId, shop.name(),
                ownerId, targetId, true, "PENDING", Instant.now(clock).plus(Duration.ofDays(7)), null, Instant.now(clock));
        when(shops.findForUpdate(shopId)).thenReturn(Optional.of(shop));
        when(shops.memberForUpdate(shopId, targetId)).thenReturn(Optional.of(target));
        when(sellers.isApprovedApplicant(targetId)).thenReturn(true);
        when(shops.ownsShop(targetId)).thenReturn(false);
        when(shops.createOwnershipTransfer(shopId, ownerId, targetId, true)).thenReturn(transfer);

        OwnershipTransferResponse result = service.requestOwnershipTransfer(ownerId, shopId,
                new TransferOwnershipRequest(targetId, true));

        assertThat(result.status()).isEqualTo("PENDING");
        verify(shops, never()).transferOwner(any(), any(), any());
    }

    @Test void ownerCannotLeaveWithoutTransferringOwnership() {
        UUID shopId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ShopResponse shop = shop(shopId, ownerId);
        when(shops.findForUpdate(shopId)).thenReturn(Optional.of(shop));
        when(shops.memberForUpdate(shopId, ownerId)).thenReturn(Optional.of(
                new MemberRow(UUID.randomUUID(), shopId, ownerId, ownerId, "ACTIVE", Instant.now(clock), Instant.now(clock))));

        assertThatThrownBy(() -> service.leaveShop(ownerId, shopId))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SHOP_OWNER_CANNOT_LEAVE"));
    }

    private ShopResponse shop(UUID shopId, UUID ownerId) {
        Instant now = Instant.now(clock);
        return new ShopResponse(shopId, ownerId, UUID.randomUUID(), "My Shop", "my-shop", null, null, null,
                "shop@example.com", "+84901234567", Map.of("province", "HCM"), "ACTIVE", now, null, now, now);
    }

    @SuppressWarnings("unused")
    private ApplicationResponse approvedApplication(UUID userId) {
        Instant now = Instant.now(clock);
        return new ApplicationResponse(UUID.randomUUID(), userId, "My Shop", "shop@example.com", "+84901234567",
                "Seller", "1234", null, "APPROVED", UUID.randomUUID(), now, null, now, now);
    }
}

