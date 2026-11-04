package com.fashioncommerce.fashioncommercebackend.seller.service;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class SellerService {
    private static final Set<String> STATUSES = Set.of("PENDING", "UNDER_REVIEW", "APPROVED", "REJECTED", "CANCELLED");

    private final SellerRepository sellers;

    public SellerService(SellerRepository sellers) {
        this.sellers = sellers;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public ApplicationResponse ownApplication(UUID userId, UUID applicationId) {
        ApplicationResponse application = required(applicationId);
        if (!application.applicantUserId().equals(userId)) throw notFound();
        return application;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public SliceResponse<ApplicationSummaryResponse> ownApplications(UUID userId, int limit, int offset) {
        validatePage(limit, offset);
        return slice(sellers.findByApplicant(userId, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public SliceResponse<ApplicationSummaryResponse> reviewQueue(String status, int limit, int offset) {
        validatePage(limit, offset);
        String normalized = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELLER_STATUS_INVALID", "Invalid seller application status");
        }
        return slice(sellers.findAll(normalized, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public ApplicationResponse applicationForReview(UUID applicationId) {
        return required(applicationId);
    }

    private ApplicationResponse required(UUID id) {
        return sellers.find(id).orElseThrow(SellerService::notFound);
    }

    private static <T> SliceResponse<T> slice(List<T> rows, int limit, int offset) {
        boolean hasMore = rows.size() > limit;
        return new SliceResponse<>(hasMore ? rows.subList(0, limit) : rows, limit, offset, hasMore);
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGINATION_INVALID", "Limit must be 1-100 and offset must be non-negative");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SELLER_APPLICATION_NOT_FOUND", "Seller application not found");
    }
}

