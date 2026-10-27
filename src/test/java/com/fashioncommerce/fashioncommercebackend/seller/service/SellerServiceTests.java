package com.fashioncommerce.fashioncommercebackend.seller.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.audit.ModerationActionRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SellerServiceTests {
    @Mock SellerRepository sellers;
    @Mock AuditLogRepository audit;
    @Mock ModerationActionRepository moderation;
    @Mock NotificationRepository notifications;
    @InjectMocks SellerService service;

    @Test void rejectionRequiresReason() {
        UUID reviewer = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(sellers.findForUpdate(applicationId)).thenReturn(Optional.of(application(applicationId, "PENDING", null)));

        assertThatThrownBy(() -> service.decide(reviewer, applicationId,
                new ReviewRequest(ReviewDecision.REJECTED, " ")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SELLER_REJECTION_NOTE_REQUIRED"));
        verify(sellers, never()).decide(any(), any(), any(), any());
    }

    @Test void assignedReviewerCannotBeReplacedDuringDecision() {
        UUID firstReviewer = UUID.randomUUID();
        UUID secondReviewer = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        when(sellers.findForUpdate(applicationId))
                .thenReturn(Optional.of(application(applicationId, "UNDER_REVIEW", firstReviewer)));

        assertThatThrownBy(() -> service.decide(secondReviewer, applicationId,
                new ReviewRequest(ReviewDecision.APPROVED, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("SELLER_APPLICATION_REVIEWER_CONFLICT"));
    }

    private ApplicationResponse application(UUID id, String status, UUID reviewer) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new ApplicationResponse(id, UUID.randomUUID(), "My Shop", "seller@example.com", "+84901234567",
                "Seller Name", "1234", null, status, reviewer, reviewer == null ? null : now,
                null, now, now);
    }
}

