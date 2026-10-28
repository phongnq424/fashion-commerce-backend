package com.fashioncommerce.fashioncommercebackend.seller.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerOnboardingDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerOnboardingRepository;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerOnboardingRepository.OnboardingRow;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellerOnboardingServiceTests {
    private final SellerOnboardingRepository onboardings = mock(SellerOnboardingRepository.class);
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private SellerOnboardingService service;

    @BeforeEach void setUp() {
        reset(onboardings, sellers, audit, notifications);
        service = new SellerOnboardingService(onboardings, sellers, audit, notifications);
    }

    @Test void householdTaxRequiresRegistrationDocument() {
        UUID userId = UUID.randomUUID();
        OnboardingRow row = row(userId, "DRAFT");
        when(onboardings.findForUpdate(row.id())).thenReturn(Optional.of(row));
        TaxProfileRequest request = new TaxProfileRequest(BusinessType.HOUSEHOLD_BUSINESS,
                "Nguyen Van A", "Ho kinh doanh A", null, "0123456789", "GCN-01",
                java.time.LocalDate.of(2025, 1, 1), "UBND", address(), "invoice@example.com", null);

        assertThatThrownBy(() -> service.saveTax(userId, row.id(), request))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SELLER_TAX_PROFILE_INVALID"));
        verify(onboardings, never()).saveTax(any(), any());
    }

    @Test void identityLegalNameMustMatchTaxProfileBeforeSubmission() {
        UUID userId = UUID.randomUUID();
        OnboardingRow row = row(userId, "DRAFT");
        when(onboardings.findForUpdate(row.id())).thenReturn(Optional.of(row));
        when(onboardings.taxProfile(row.id())).thenReturn(Optional.of(new TaxProfileResponse(
                BusinessType.INDIVIDUAL, "Nguyen Van A", null, null, "0123456789", null,
                null, null, Map.of(), "invoice@example.com", null, "DRAFT", null, null, null)));

        assertThatThrownBy(() -> service.submitIdentity(userId, row.id(),
                new IdentityVerificationRequest("Tran Van B", "1234", "front", "back", "selfie")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("IDENTITY_LEGAL_NAME_MISMATCH"));
        verify(onboardings, never()).saveIdentity(any(), any());
    }

    @Test void unifiedRejectionRequiresReason() {
        UUID reviewerId = UUID.randomUUID();
        OnboardingRow row = row(UUID.randomUUID(), "PENDING_REVIEW");
        when(onboardings.findForUpdate(row.id())).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> service.decide(reviewerId, row.id(),
                new ReviewRequest(ReviewDecision.REJECTED, " ")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SELLER_REJECTION_NOTE_REQUIRED"));
        verify(onboardings, never()).rejectReview(any(), any(), any());
    }

    @Test void completeSubmitsOnePackageAndRejectsIncompleteDraft() {
        UUID userId = UUID.randomUUID();
        OnboardingRow row = row(userId, "DRAFT");
        when(onboardings.findForUpdate(row.id())).thenReturn(Optional.of(row));
        when(onboardings.submitForReview(row.id())).thenReturn(false);

        assertThatThrownBy(() -> service.complete(userId, row.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SELLER_ONBOARDING_INCOMPLETE"));
        verify(onboardings).submitForReview(row.id());
    }

    @Test void completeSubmitsWholeDraftForOneReview() {
        UUID userId = UUID.randomUUID();
        OnboardingRow draft = row(userId, "DRAFT");
        OnboardingRow pending = row(draft.id(), userId, draft.shopId(), "PENDING_REVIEW");
        OnboardingResponse expected = mock(OnboardingResponse.class);
        when(onboardings.findForUpdate(draft.id())).thenReturn(Optional.of(draft));
        when(onboardings.submitForReview(draft.id())).thenReturn(true);
        when(onboardings.find(draft.id())).thenReturn(Optional.of(pending));
        when(onboardings.response(pending)).thenReturn(expected);

        assertThat(service.complete(userId, draft.id())).isSameAs(expected);
        verify(onboardings).submitForReview(draft.id());
        verify(onboardings, never()).approveAndComplete(any(), any(), any(), any());
    }

    @Test void oneModeratorApprovalActivatesSellerAndShop() {
        UUID reviewerId = UUID.randomUUID();
        OnboardingRow pending = row(UUID.randomUUID(), "PENDING_REVIEW");
        OnboardingRow completed = row(pending.id(), pending.applicantUserId(), pending.shopId(), "COMPLETED");
        OnboardingResponse expected = mock(OnboardingResponse.class);
        when(onboardings.findForUpdate(pending.id())).thenReturn(Optional.of(pending));
        when(onboardings.approveAndComplete(pending.id(), pending.shopId(), reviewerId, "Valid"))
                .thenReturn(UUID.randomUUID());
        when(onboardings.find(pending.id())).thenReturn(Optional.of(completed));
        when(onboardings.response(completed)).thenReturn(expected);

        assertThat(service.decide(reviewerId, pending.id(),
                new ReviewRequest(ReviewDecision.APPROVED, "Valid"))).isSameAs(expected);
        verify(onboardings).approveAndComplete(pending.id(), pending.shopId(), reviewerId, "Valid");
        verify(onboardings, never()).rejectReview(any(), any(), any());
    }

    private OnboardingRow row(UUID userId, String status) {
        UUID shopId = UUID.randomUUID();
        return row(UUID.randomUUID(), userId, shopId, status);
    }

    private OnboardingRow row(UUID id, UUID userId, UUID shopId, String status) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new OnboardingRow(id, userId, shopId, status,
                new ShopProfileResponse(shopId, "Shop", "shop-12345678", "shop@example.com",
                        "+84901234567", null, null, null, "DRAFT"),
                null, null, null, null, null, now, now);
    }

    private RegisteredAddress address() {
        return new RegisteredAddress("79", "Ho Chi Minh", "26734", "Ben Nghe", "1 Nguyen Hue");
    }
}
