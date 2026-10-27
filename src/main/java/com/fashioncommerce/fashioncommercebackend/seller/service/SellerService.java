package com.fashioncommerce.fashioncommercebackend.seller.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.audit.ModerationActionRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
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
    private final AuditLogRepository audit;
    private final ModerationActionRepository moderation;
    private final NotificationRepository notifications;

    public SellerService(SellerRepository sellers, AuditLogRepository audit,
                         ModerationActionRepository moderation, NotificationRepository notifications) {
        this.sellers = sellers;
        this.audit = audit;
        this.moderation = moderation;
        this.notifications = notifications;
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public ApplicationResponse apply(UUID userId, ApplyRequest request) {
        if (sellers.isApprovedApplicant(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELLER_ALREADY_APPROVED",
                    "The user is already an approved seller");
        }
        UUID id = UUID.randomUUID();
        ApplicationResponse created = sellers.create(userId, id,
                request.proposedShopName().strip(), request.contactEmail().strip().toLowerCase(Locale.ROOT),
                request.contactPhone().strip(), request.legalName().strip(), trimToNull(request.legalIdentifierLast4()),
                trimToNull(request.documentKey()));
        audit.record(userId, null, "SELLER_APPLICATION_SUBMITTED", "SELLER_APPLICATION", id,
                Map.of("status", "PENDING"));
        return created;
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
    public SliceResponse<ApplicationResponse> ownApplications(UUID userId, int limit, int offset) {
        validatePage(limit, offset);
        return slice(sellers.findByApplicant(userId, limit + 1, offset), limit, offset);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public void cancel(UUID userId, UUID applicationId) {
        ApplicationResponse application = sellers.findForUpdate(applicationId).orElseThrow(SellerService::notFound);
        if (!application.applicantUserId().equals(userId)) throw notFound();
        if (!sellers.cancel(applicationId, userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELLER_APPLICATION_NOT_CANCELLABLE",
                    "Only a pending application can be cancelled");
        }
        audit.record(userId, null, "SELLER_APPLICATION_CANCELLED", "SELLER_APPLICATION", applicationId,
                Map.of("from", application.status(), "to", "CANCELLED"));
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public ApplicationResponse update(UUID userId, UUID applicationId, ApplyRequest request) {
        ApplicationResponse before = sellers.findForUpdate(applicationId).orElseThrow(SellerService::notFound);
        if (!before.applicantUserId().equals(userId)) throw notFound();
        if (!sellers.updatePending(applicationId, userId, request.proposedShopName().strip(),
                request.contactEmail().strip().toLowerCase(Locale.ROOT), request.contactPhone().strip(),
                request.legalName().strip(), trimToNull(request.legalIdentifierLast4()), trimToNull(request.documentKey()))) {
            throw new ApiException(HttpStatus.CONFLICT, "SELLER_APPLICATION_NOT_EDITABLE",
                    "Only a pending application can be edited");
        }
        audit.record(userId, null, "SELLER_APPLICATION_UPDATED", "SELLER_APPLICATION", applicationId, Map.of());
        return required(applicationId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public SliceResponse<ApplicationResponse> reviewQueue(String status, int limit, int offset) {
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

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public ApplicationResponse startReview(UUID reviewerId, UUID applicationId) {
        ApplicationResponse before = sellers.findForUpdate(applicationId).orElseThrow(SellerService::notFound);
        if (!sellers.markUnderReview(applicationId, reviewerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELLER_APPLICATION_STATE_CONFLICT",
                    "Only a pending application can enter review");
        }
        audit.record(reviewerId, null, "SELLER_APPLICATION_REVIEW_STARTED", "SELLER_APPLICATION", applicationId,
                Map.of("from", before.status(), "to", "UNDER_REVIEW"));
        return required(applicationId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public ApplicationResponse decide(UUID reviewerId, UUID applicationId, ReviewRequest request) {
        ApplicationResponse before = sellers.findForUpdate(applicationId).orElseThrow(SellerService::notFound);
        if ("UNDER_REVIEW".equals(before.status()) && before.reviewedBy() != null
                && !before.reviewedBy().equals(reviewerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELLER_APPLICATION_REVIEWER_CONFLICT",
                    "The application is being reviewed by another moderator");
        }
        String note = trimToNull(request.note());
        if (request.decision() == ReviewDecision.REJECTED && note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELLER_REJECTION_NOTE_REQUIRED",
                    "A rejection reason is required");
        }
        if (!sellers.decide(applicationId, reviewerId, request.decision().name(), note)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELLER_APPLICATION_STATE_CONFLICT",
                    "The application has already reached a final state");
        }
        audit.record(reviewerId, null, "SELLER_APPLICATION_" + request.decision().name(),
                "SELLER_APPLICATION", applicationId,
                Map.of("from", before.status(), "to", request.decision().name()));
        moderation.sellerDecision(reviewerId, applicationId,
                request.decision() == ReviewDecision.APPROVED ? "APPROVE" : "REJECT",
                note == null ? "Approved" : note, before.status(), request.decision().name());
        notifications.create(before.applicantUserId(), "SELLER_APPLICATION_" + request.decision().name(),
                "Seller application reviewed",
                request.decision() == ReviewDecision.APPROVED
                        ? "Your seller application was approved."
                        : "Your seller application was rejected.",
                Map.of("applicationId", applicationId, "status", request.decision().name()));
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

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SELLER_APPLICATION_NOT_FOUND", "Seller application not found");
    }
}

