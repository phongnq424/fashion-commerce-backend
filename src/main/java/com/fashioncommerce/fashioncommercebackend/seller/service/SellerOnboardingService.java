package com.fashioncommerce.fashioncommercebackend.seller.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerOnboardingDtos.*;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerOnboardingRepository;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerOnboardingRepository.OnboardingRow;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SellerOnboardingService {
    private final SellerOnboardingRepository onboardings;
    private final SellerRepository sellers;
    private final AuditLogRepository audit;
    private final NotificationRepository notifications;

    public SellerOnboardingService(SellerOnboardingRepository onboardings, SellerRepository sellers,
                                   AuditLogRepository audit, NotificationRepository notifications) {
        this.onboardings = onboardings;
        this.sellers = sellers;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public OnboardingResponse start(UUID userId, ShopProfileRequest request) {
        if (sellers.isApprovedApplicant(userId)) {
            throw conflict("SELLER_ALREADY_APPROVED", "The user is already an approved seller");
        }
        if (onboardings.activeForUser(userId).isPresent()) {
            throw conflict("SELLER_ONBOARDING_ACTIVE_EXISTS", "An active seller onboarding already exists");
        }
        OnboardingRow created = onboardings.create(userId, request, slug(request.name()));
        audit.record(userId, created.shopId(), "SELLER_ONBOARDING_STARTED", "SELLER_ONBOARDING", created.id(),
                Map.of("status", created.status()));
        return onboardings.response(created);
    }

    @Transactional(readOnly = true)
    public OnboardingResponse mine(UUID userId) {
        return onboardings.response(onboardings.activeForUser(userId).orElseThrow(SellerOnboardingService::notFound));
    }

    @Transactional(readOnly = true)
    public OnboardingResponse getOwn(UUID userId, UUID onboardingId) {
        return onboardings.response(owned(userId, onboardingId, false));
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public OnboardingResponse updateShop(UUID userId, UUID onboardingId, ShopProfileRequest request) {
        OnboardingRow row = owned(userId, onboardingId, true);
        requireEditable(row);
        onboardings.updateShop(row.shopId(), request);
        audit.record(userId, row.shopId(), "SELLER_ONBOARDING_SHOP_UPDATED", "SELLER_ONBOARDING", row.id(), Map.of());
        return response(onboardingId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public PickupAddressResponse savePickup(UUID userId, UUID onboardingId, PickupAddressRequest request) {
        OnboardingRow row = owned(userId, onboardingId, true);
        requireEditable(row);
        PickupAddressResponse address = onboardings.upsertPickupAddress(row.id(), row.shopId(), request);
        audit.record(userId, row.shopId(), "SELLER_ONBOARDING_PICKUP_SAVED", "SHOP_PICKUP_ADDRESS", address.id(),
                Map.of("defaultPickup", address.defaultPickup()));
        return address;
    }

    @Transactional(readOnly = true)
    public List<ShippingProviderResponse> shippingProviders(UUID userId, UUID onboardingId) {
        OnboardingRow row = owned(userId, onboardingId, false);
        return onboardings.shippingProviders(row.shopId());
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public List<ShippingProviderResponse> configureShipping(UUID userId, UUID onboardingId,
                                                            ShippingConfigurationRequest request) {
        OnboardingRow row = owned(userId, onboardingId, true);
        requireEditable(row);
        if (!onboardings.activeAddressBelongsToShop(request.pickupAddressId(), row.shopId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PICKUP_ADDRESS_INVALID",
                    "The pickup address does not belong to this shop");
        }
        Map<String, ShippingSelection> selections;
        try {
            selections = request.providers().stream().collect(Collectors.toMap(
                    item -> normalizeCode(item.providerCode()), Function.identity(), (a, b) -> {
                        throw new IllegalArgumentException("duplicate");
                    }, LinkedHashMap::new));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHIPPING_PROVIDER_DUPLICATED",
                    "Each shipping provider may appear only once");
        }
        if (selections.values().stream().noneMatch(ShippingSelection::enabled)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHIPPING_PROVIDER_REQUIRED",
                    "At least one shipping provider must be enabled");
        }
        Map<String, UUID> providerIds = onboardings.activeProviderIds(selections.keySet());
        if (!providerIds.keySet().containsAll(selections.keySet())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHIPPING_PROVIDER_INVALID",
                    "One or more shipping providers are unavailable");
        }
        onboardings.configureShipping(row.id(), row.shopId(), request.pickupAddressId(), selections.values(), providerIds);
        audit.record(userId, row.shopId(), "SELLER_ONBOARDING_SHIPPING_CONFIGURED", "SELLER_ONBOARDING", row.id(),
                Map.of("enabledProviders", selections.values().stream().filter(ShippingSelection::enabled).count()));
        return onboardings.shippingProviders(row.shopId());
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public TaxProfileResponse saveTax(UUID userId, UUID onboardingId, TaxProfileRequest request) {
        OnboardingRow row = owned(userId, onboardingId, true);
        requireEditable(row);
        validateTax(request);
        onboardings.saveTax(row.id(), request);
        audit.record(userId, row.shopId(), "SELLER_ONBOARDING_TAX_SAVED", "SELLER_TAX_PROFILE", row.id(),
                Map.of("businessType", request.businessType().name()));
        return onboardings.taxProfile(row.id()).orElseThrow();
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public OnboardingResponse submitIdentity(UUID userId, UUID onboardingId, IdentityVerificationRequest request) {
        OnboardingRow row = owned(userId, onboardingId, true);
        requireEditable(row);
        TaxProfileResponse tax = onboardings.taxProfile(row.id()).orElseThrow(() ->
                conflict("SELLER_TAX_PROFILE_REQUIRED", "Complete tax information before CCCD information"));
        String expectedName = tax.businessType() == BusinessType.ENTERPRISE
                ? tax.legalRepresentativeName() : tax.legalName();
        if (expectedName == null || !sameName(expectedName, request.legalName())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDENTITY_LEGAL_NAME_MISMATCH",
                    "The CCCD legal name must match the tax profile");
        }
        onboardings.saveIdentity(row.id(), request);
        audit.record(userId, row.shopId(), "SELLER_IDENTITY_SAVED", "SELLER_IDENTITY_VERIFICATION", row.id(),
                Map.of("documentType", "CCCD"));
        return response(row.id());
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public OnboardingResponse acceptPolicy(UUID userId, UUID onboardingId, PolicyAcceptanceRequest request) {
        OnboardingRow row = owned(userId, onboardingId, true);
        requireEditable(row);
        if (!onboardings.acceptPolicy(row.id(), request.policyVersion().strip())) {
            boolean alreadyAccepted = onboardings.acceptedPolicies(row.id()).contains(request.policyVersion().strip());
            if (!alreadyAccepted) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "SELLER_POLICY_VERSION_INVALID",
                        "The policy version is not active");
            }
        }
        audit.record(userId, row.shopId(), "SELLER_POLICY_ACCEPTED", "SELLER_ONBOARDING", row.id(),
                Map.of("policyVersion", request.policyVersion().strip()));
        return response(row.id());
    }

    @Transactional(readOnly = true)
    public List<PolicyVersionResponse> policies(UUID userId, UUID onboardingId) {
        OnboardingRow row = owned(userId, onboardingId, false);
        return onboardings.policies(row.id());
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public OnboardingResponse complete(UUID userId, UUID onboardingId) {
        OnboardingRow row = owned(userId, onboardingId, true);
        requireEditable(row);
        if (!onboardings.submitForReview(row.id())) {
            throw conflict("SELLER_ONBOARDING_INCOMPLETE",
                    "Complete shop, pickup, shipping, tax, CCCD and all required policies before submitting");
        }
        audit.record(userId, row.shopId(), "SELLER_ONBOARDING_SUBMITTED", "SELLER_ONBOARDING", row.id(),
                Map.of("status", "PENDING_REVIEW"));
        notifications.create(userId, "SELLER_ONBOARDING_SUBMITTED", "Seller application submitted",
                "Your complete seller application is waiting for moderator review.", Map.of("shopId", row.shopId()));
        return response(row.id());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public OnboardingResponse moderationGet(UUID onboardingId) {
        return response(onboardingId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public SliceResponse<OnboardingResponse> reviewQueue(String status, int limit, int offset) {
        validatePage(limit, offset);
        String normalized = reviewStatus(status);
        List<UUID> ids = onboardings.idsByReviewStatus(normalized, limit + 1, offset);
        return slice(ids, limit, offset);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.review')")
    public OnboardingResponse decide(UUID reviewerId, UUID onboardingId, ReviewRequest request) {
        OnboardingRow row = required(onboardingId, true);
        if (!"PENDING_REVIEW".equals(row.status())) throw stateConflict();
        String note = reviewNote(request);
        if (request.decision() == ReviewDecision.APPROVED) {
            UUID applicationId = onboardings.approveAndComplete(row.id(), row.shopId(), reviewerId, note);
            audit.record(reviewerId, row.shopId(), "SELLER_ONBOARDING_APPROVED", "SELLER_ONBOARDING", row.id(),
                    Map.of("applicationId", applicationId, "shopStatus", "ACTIVE"));
            notifications.create(row.applicantUserId(), "SELLER_ONBOARDING_APPROVED", "Seller account activated",
                    "Your seller application was approved and your shop is active.", Map.of("shopId", row.shopId()));
        } else {
            onboardings.rejectReview(row.id(), reviewerId, note);
            audit.record(reviewerId, row.shopId(), "SELLER_ONBOARDING_REJECTED", "SELLER_ONBOARDING", row.id(),
                    Map.of("reason", note));
            notifications.create(row.applicantUserId(), "SELLER_ONBOARDING_REJECTED", "Seller application rejected",
                    "Your seller application needs changes before it can be resubmitted.",
                    Map.of("onboardingId", row.id(), "reason", note));
        }
        return response(row.id());
    }

    private OnboardingRow owned(UUID userId, UUID id, boolean lock) {
        OnboardingRow row = required(id, lock);
        if (!row.applicantUserId().equals(userId)) throw notFound();
        return row;
    }

    private OnboardingRow required(UUID id, boolean lock) {
        return (lock ? onboardings.findForUpdate(id) : onboardings.find(id))
                .orElseThrow(SellerOnboardingService::notFound);
    }

    private OnboardingResponse response(UUID id) { return onboardings.response(required(id, false)); }

    private void requireEditable(OnboardingRow row) {
        if (!Set.of("DRAFT", "REJECTED").contains(row.status())) {
            throw conflict("SELLER_ONBOARDING_NOT_EDITABLE", "The onboarding cannot be edited in its current state");
        }
    }

    private void validateTax(TaxProfileRequest request) {
        switch (request.businessType()) {
            case INDIVIDUAL -> require(request.legalName(), "legalName is required for an individual seller");
            case HOUSEHOLD_BUSINESS -> {
                require(request.legalName(), "legalName of the household representative is required");
                requireBusinessRegistration(request);
            }
            case ENTERPRISE -> {
                require(request.legalRepresentativeName(), "legalRepresentativeName is required for an enterprise");
                requireBusinessRegistration(request);
            }
        }
    }

    private void requireBusinessRegistration(TaxProfileRequest request) {
        require(request.businessName(), "businessName is required");
        require(request.businessRegistrationNumber(), "businessRegistrationNumber is required");
        if (request.issuedDate() == null) invalidTax("issuedDate is required");
        require(request.issuedBy(), "issuedBy is required");
        require(request.registrationDocumentKey(), "registrationDocumentKey is required");
    }

    private void require(String value, String message) {
        if (value == null || value.isBlank()) invalidTax(message);
    }

    private void invalidTax(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "SELLER_TAX_PROFILE_INVALID", message);
    }

    private String reviewNote(ReviewRequest request) {
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (request.decision() == ReviewDecision.REJECTED && note == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELLER_REJECTION_NOTE_REQUIRED",
                    "A rejection reason is required");
        }
        return note;
    }

    private SliceResponse<OnboardingResponse> slice(List<UUID> ids, int limit, int offset) {
        boolean hasMore = ids.size() > limit;
        List<UUID> page = hasMore ? ids.subList(0, limit) : ids;
        return new SliceResponse<>(page.stream().map(this::response).toList(), limit, offset, hasMore);
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGINATION_INVALID",
                    "Limit must be 1-100 and offset must be non-negative");
        }
    }

    private static String reviewStatus(String value) {
        String status = value == null || value.isBlank() ? "PENDING_REVIEW" : value.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING_REVIEW", "COMPLETED", "REJECTED").contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REVIEW_STATUS_INVALID", "Invalid review status");
        }
        return status;
    }

    private static boolean sameName(String left, String right) {
        return Normalizer.normalize(left.strip(), Normalizer.Form.NFKC)
                .equalsIgnoreCase(Normalizer.normalize(right.strip(), Normalizer.Form.NFKC));
    }

    private static String slug(String name) {
        String base = Normalizer.normalize(name.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) base = "shop";
        if (base.length() > 160) base = base.substring(0, 160).replaceAll("-$", "");
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String normalizeCode(String code) { return code.strip().toUpperCase(Locale.ROOT); }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SELLER_ONBOARDING_NOT_FOUND", "Seller onboarding not found");
    }
    private static ApiException stateConflict() {
        return conflict("SELLER_ONBOARDING_STATE_CONFLICT", "The onboarding state has changed");
    }
    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
