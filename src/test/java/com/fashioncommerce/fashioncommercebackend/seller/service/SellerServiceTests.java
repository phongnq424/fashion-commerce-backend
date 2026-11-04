package com.fashioncommerce.fashioncommercebackend.seller.service;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.ApplicationResponse;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SellerServiceTests {
    private final SellerRepository sellers = mock(SellerRepository.class);
    private final SellerService service = new SellerService(sellers);

    @Test void ownApplicationDoesNotExposeAnotherApplicant() {
        UUID applicationId = UUID.randomUUID();
        when(sellers.find(applicationId)).thenReturn(Optional.of(application(applicationId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.ownApplication(UUID.randomUUID(), applicationId))
                .isInstanceOf(ApiException.class).hasMessage("Seller application not found");
    }

    @Test void invalidReviewStatusIsRejectedBeforeQueryingDatabase() {
        assertThatThrownBy(() -> service.reviewQueue("UNKNOWN", 20, 0))
                .isInstanceOf(ApiException.class).hasMessage("Invalid seller application status");
        verifyNoInteractions(sellers);
    }

    private ApplicationResponse application(UUID id, UUID applicantId) {
        Instant now = Instant.parse("2026-10-21T07:00:00Z");
        return new ApplicationResponse(id, applicantId, "Shop", "seller@example.com", "+84901234567",
                "Seller", "1234", null, "APPROVED", null, null, null, now, now);
    }
}
