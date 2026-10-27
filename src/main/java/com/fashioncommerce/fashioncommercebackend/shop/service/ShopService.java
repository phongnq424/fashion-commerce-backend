package com.fashioncommerce.fashioncommercebackend.shop.service;

import com.fashioncommerce.fashioncommercebackend.common.audit.AuditLogRepository;
import com.fashioncommerce.fashioncommercebackend.common.audit.ModerationActionRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationRepository;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.seller.repository.SellerRepository;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import com.fashioncommerce.fashioncommercebackend.shop.repository.ShopRepository;
import com.fashioncommerce.fashioncommercebackend.shop.repository.ShopRepository.MemberRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.*;

@Service
public class ShopService {
    private static final Set<String> SHOP_STATUSES = Set.of("ACTIVE", "SUSPENDED", "CLOSED");

    private final ShopRepository shops;
    private final SellerRepository sellers;
    private final AuditLogRepository audit;
    private final ModerationActionRepository moderation;
    private final NotificationRepository notifications;
    private final Clock clock;

    public ShopService(ShopRepository shops, SellerRepository sellers, AuditLogRepository audit,
                       ModerationActionRepository moderation, NotificationRepository notifications, Clock clock) {
        this.shops = shops;
        this.sellers = sellers;
        this.audit = audit;
        this.moderation = moderation;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'seller.apply')")
    public ShopResponse create(UUID userId, CreateRequest request) {
        sellers.approvedApplicationForUpdate(request.applicationId(), userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SHOP_APPLICATION_NOT_APPROVED",
                        "An approved seller application owned by the current user is required"));
        if (shops.ownsShop(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER_LIMIT_REACHED", "A seller can own only one shop");
        }

        UUID shopId = UUID.randomUUID();
        ShopResponse created = shops.create(shopId, userId, request.applicationId(), request);
        UUID memberId = shops.createActiveMember(shopId, userId, userId);
        UUID ownerRole = requiredRole(shops.shopRoles(), "SHOP_OWNER");
        shops.addRole(memberId, ownerRole, userId);
        audit.record(userId, shopId, "SHOP_CREATED", "SHOP", shopId,
                Map.of("applicationId", request.applicationId(), "status", "ACTIVE"));
        return created;
    }

    @Transactional(readOnly = true)
    public SliceResponse<ShopResponse> mine(UUID userId, int limit, int offset) {
        validatePage(limit, offset);
        return slice(shops.findForUser(userId, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.profile.read')")
    public ShopResponse get(UUID shopId) {
        return requiredShop(shopId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.profile.update')")
    public ShopResponse update(UUID actorId, UUID shopId, UpdateRequest request) {
        if (request.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHOP_UPDATE_EMPTY", "At least one field must be supplied");
        }
        ShopResponse before = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        requireActive(before);
        shops.update(shopId, request);
        audit.record(actorId, shopId, "SHOP_PROFILE_UPDATED", "SHOP", shopId, Map.of());
        return requiredShop(shopId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.profile.read')")
    public SettingsResponse settings(UUID shopId) {
        requiredShop(shopId);
        return shops.settings(shopId);
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.settings.manage')")
    public SettingsResponse updateSettings(UUID actorId, UUID shopId, UpdateSettingsRequest request) {
        ShopResponse shop = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        requireActive(shop);
        shops.updateSettings(shopId, request);
        audit.record(actorId, shopId, "SHOP_SETTINGS_UPDATED", "SHOP_SETTINGS", shopId, Map.of());
        return shops.settings(shopId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.staff.read')")
    public SliceResponse<MemberResponse> members(UUID shopId, int limit, int offset) {
        validatePage(limit, offset);
        return slice(shops.members(shopId, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.staff.roles.manage')")
    public List<RoleResponse> assignableRoles(UUID shopId) {
        requiredShop(shopId);
        return shops.assignableShopRoles();
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.staff.invite')")
    public MemberResponse invite(UUID actorId, UUID shopId, InviteMemberRequest request) {
        ShopResponse shop = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        requireActive(shop);
        var user = shops.activeUserByEmail(request.email().strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SHOP_INVITEE_NOT_FOUND",
                        "No active account was found for that email"));
        if (user.id().equals(actorId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_SELF_INVITE", "You cannot invite yourself");
        }

        List<String> roles = validatedAssignableRoles(request.roles());
        Optional<MemberRow> existing = shops.memberForUpdate(shopId, user.id());
        UUID memberId;
        if (existing.isPresent()) {
            if (Set.of("ACTIVE", "INVITED").contains(existing.get().status())) {
                throw new ApiException(HttpStatus.CONFLICT, "SHOP_MEMBER_EXISTS",
                        "The user is already a member or has a pending invitation");
            }
            memberId = existing.get().id();
            shops.reinvite(memberId, actorId);
        }
        else {
            memberId = shops.createInvitation(shopId, user.id(), actorId);
        }
        shops.replaceRoles(memberId, roleIds(roles), actorId);
        audit.record(actorId, shopId, "SHOP_MEMBER_INVITED", "SHOP_MEMBER", memberId,
                Map.of("userId", user.id(), "roles", roles));
        notifications.create(user.id(), "SHOP_MEMBER_INVITED", "Shop invitation",
                "You were invited to join " + shop.name() + ".",
                Map.of("shopId", shopId, "memberId", memberId));
        return shops.member(shopId, memberId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> invitations(UUID userId) {
        return shops.invitations(userId);
    }

    @Transactional
    public MemberResponse acceptInvitation(UUID userId, UUID memberId) {
        MemberRow snapshot = shops.invitation(memberId, userId).orElseThrow(ShopService::invitationNotFound);
        ShopResponse shop = shops.findForUpdate(snapshot.shopId()).orElseThrow(ShopService::shopNotFound);
        MemberRow invitation = shops.invitationForUpdate(memberId, userId).orElseThrow(ShopService::invitationNotFound);
        if (!invitation.shopId().equals(shop.id())) throw invitationNotFound();
        requireActive(shop);
        if (!shops.acceptInvitation(memberId, userId)) throw invitationNotFound();
        audit.record(userId, invitation.shopId(), "SHOP_INVITATION_ACCEPTED", "SHOP_MEMBER", memberId, Map.of());
        if (invitation.invitedBy() != null) {
            notifications.create(invitation.invitedBy(), "SHOP_INVITATION_ACCEPTED", "Shop invitation accepted",
                    "A user accepted your shop invitation.", Map.of("shopId", invitation.shopId(), "memberId", memberId));
        }
        return shops.member(invitation.shopId(), memberId).orElseThrow();
    }

    @Transactional
    public void declineInvitation(UUID userId, UUID memberId) {
        MemberRow invitation = shops.invitationForUpdate(memberId, userId).orElseThrow(ShopService::invitationNotFound);
        if (!shops.declineInvitation(memberId, userId)) throw invitationNotFound();
        audit.record(userId, invitation.shopId(), "SHOP_INVITATION_DECLINED", "SHOP_MEMBER", memberId, Map.of());
        if (invitation.invitedBy() != null) {
            notifications.create(invitation.invitedBy(), "SHOP_INVITATION_DECLINED", "Shop invitation declined",
                    "A user declined your shop invitation.", Map.of("shopId", invitation.shopId(), "memberId", memberId));
        }
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.staff.roles.manage')")
    public MemberResponse updateMemberRoles(UUID actorId, UUID shopId, UUID memberId,
                                            UpdateMemberRolesRequest request) {
        ShopResponse shop = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        requireActive(shop);
        MemberRow member = shops.memberByIdForUpdate(shopId, memberId).orElseThrow(ShopService::memberNotFound);
        if (member.userId().equals(shop.ownerUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER_ROLES_IMMUTABLE",
                    "Transfer ownership before changing the owner's role");
        }
        if (!"ACTIVE".equals(member.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_MEMBER_NOT_ACTIVE", "Only active member roles can be changed");
        }
        List<String> roles = validatedAssignableRoles(request.roles());
        shops.replaceRoles(memberId, roleIds(roles), actorId);
        audit.record(actorId, shopId, "SHOP_MEMBER_ROLES_UPDATED", "SHOP_MEMBER", memberId,
                Map.of("roles", roles));
        notifications.create(member.userId(), "SHOP_MEMBER_ROLES_UPDATED", "Shop roles updated",
                "Your roles in a shop were updated.", Map.of("shopId", shopId, "roles", roles));
        return shops.member(shopId, memberId).orElseThrow();
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.staff.remove')")
    public void removeMember(UUID actorId, UUID shopId, UUID memberId) {
        ShopResponse shop = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        requireActive(shop);
        MemberRow member = shops.memberByIdForUpdate(shopId, memberId).orElseThrow(ShopService::memberNotFound);
        if (member.userId().equals(shop.ownerUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER_CANNOT_BE_REMOVED",
                    "Transfer ownership before removing the owner");
        }
        if ("DISABLED".equals(member.status())) throw memberNotFound();
        shops.disableMember(memberId);
        shops.replaceRoles(memberId, List.of(), actorId);
        audit.record(actorId, shopId, "SHOP_MEMBER_REMOVED", "SHOP_MEMBER", memberId,
                Map.of("userId", member.userId()));
        notifications.create(member.userId(), "SHOP_MEMBER_REMOVED", "Removed from shop",
                "Your shop membership was disabled.", Map.of("shopId", shopId));
    }

    @Transactional
    public void leaveShop(UUID userId, UUID shopId) {
        ShopResponse shop = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        MemberRow member = shops.memberForUpdate(shopId, userId).orElseThrow(ShopService::memberNotFound);
        if (shop.ownerUserId().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER_CANNOT_LEAVE",
                    "Transfer ownership or close the shop before leaving");
        }
        if (!"ACTIVE".equals(member.status())) throw memberNotFound();
        shops.disableMember(member.id());
        shops.replaceRoles(member.id(), List.of(), userId);
        audit.record(userId, shopId, "SHOP_MEMBER_LEFT", "SHOP_MEMBER", member.id(), Map.of());
        notifications.create(shop.ownerUserId(), "SHOP_MEMBER_LEFT", "A member left the shop",
                "A staff member left " + shop.name() + ".", Map.of("shopId", shopId, "userId", userId));
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.ownership.transfer')")
    public OwnershipTransferResponse requestOwnershipTransfer(UUID actorId, UUID shopId,
                                                              TransferOwnershipRequest request) {
        ShopResponse shop = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        requireActive(shop);
        if (!shop.ownerUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_OWNER_REQUIRED", "Only the current owner can transfer ownership");
        }
        if (shop.ownerUserId().equals(request.newOwnerUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER_UNCHANGED", "The selected user already owns this shop");
        }
        MemberRow target = shops.memberForUpdate(shopId, request.newOwnerUserId()).orElseThrow(ShopService::memberNotFound);
        if (!"ACTIVE".equals(target.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_NEW_OWNER_NOT_ACTIVE",
                    "The new owner must be an active shop member");
        }
        if (!sellers.isApprovedApplicant(target.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_NEW_OWNER_NOT_APPROVED_SELLER",
                    "The new owner must have an approved seller application");
        }
        if (shops.ownsShop(target.userId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER_LIMIT_REACHED", "The new owner already owns a shop");
        }
        shops.expirePendingOwnershipTransfers(shopId);
        OwnershipTransferResponse transfer = shops.createOwnershipTransfer(shopId, actorId, target.userId(),
                request.keepPreviousOwnerAsManager());
        audit.record(actorId, shopId, "SHOP_OWNERSHIP_TRANSFER_REQUESTED", "SHOP_OWNERSHIP_TRANSFER", transfer.id(),
                Map.of("newOwnerUserId", target.userId(),
                        "previousOwnerKeptAsManager", request.keepPreviousOwnerAsManager()));
        notifications.create(target.userId(), "SHOP_OWNERSHIP_TRANSFER_REQUESTED", "Shop ownership transfer",
                "You were asked to become the owner of " + shop.name() + ".",
                Map.of("shopId", shopId, "transferId", transfer.id(), "expiresAt", transfer.expiresAt()));
        return transfer;
    }

    @Transactional(readOnly = true)
    public List<OwnershipTransferResponse> ownershipTransferRequests(UUID userId) {
        return shops.ownershipTransfersForTarget(userId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.ownership.transfer')")
    public List<OwnershipTransferResponse> shopOwnershipTransfers(UUID shopId) {
        return shops.ownershipTransfersForShop(shopId);
    }

    @Transactional
    public ShopResponse acceptOwnershipTransfer(UUID userId, UUID transferId) {
        OwnershipTransferResponse snapshot = pendingTransfer(transferId, false);
        if (!snapshot.toUserId().equals(userId)) throw transferNotFound();
        ShopResponse shop = shops.findForUpdate(snapshot.shopId()).orElseThrow(ShopService::shopNotFound);
        OwnershipTransferResponse transfer = pendingTransfer(transferId, true);
        if (!transfer.toUserId().equals(userId) || !transfer.shopId().equals(shop.id())) throw transferNotFound();
        requireActive(shop);
        if (!shop.ownerUserId().equals(transfer.fromOwnerUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_TRANSFER_OWNER_CHANGED",
                    "The shop owner changed after this transfer was requested");
        }
        MemberRow target = shops.memberForUpdate(shop.id(), userId).orElseThrow(ShopService::memberNotFound);
        if (!"ACTIVE".equals(target.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_NEW_OWNER_NOT_ACTIVE",
                    "The new owner must be an active shop member");
        }
        var targetApplication = sellers.approvedApplicationForUserForUpdate(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "SHOP_NEW_OWNER_NOT_APPROVED_SELLER",
                        "The new owner must have an approved seller application"));
        if (shops.ownsShop(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_OWNER_LIMIT_REACHED", "The new owner already owns a shop");
        }

        MemberRow previous = shops.memberForUpdate(shop.id(), transfer.fromOwnerUserId())
                .orElseThrow(ShopService::memberNotFound);
        Map<String, UUID> roles = shops.shopRoles();
        UUID ownerRole = requiredRole(roles, "SHOP_OWNER");
        shops.transferOwner(shop.id(), userId, targetApplication.id());
        shops.removeRole(previous.id(), ownerRole);
        shops.addRole(target.id(), ownerRole, userId);
        if (transfer.keepPreviousOwnerAsManager()) {
            shops.addRole(previous.id(), requiredRole(roles, "SHOP_MANAGER"), userId);
        }
        else {
            shops.disableMember(previous.id());
            shops.replaceRoles(previous.id(), List.of(), userId);
        }
        if (!shops.decideOwnershipTransfer(transferId, "ACCEPTED")) throw transferStateConflict();
        audit.record(userId, shop.id(), "SHOP_OWNERSHIP_TRANSFERRED", "SHOP_OWNERSHIP_TRANSFER", transferId,
                Map.of("previousOwnerUserId", transfer.fromOwnerUserId(), "newOwnerUserId", userId,
                        "newOwnerApplicationId", targetApplication.id(),
                        "previousOwnerKeptAsManager", transfer.keepPreviousOwnerAsManager()));
        notifications.create(transfer.fromOwnerUserId(), "SHOP_OWNERSHIP_TRANSFER_ACCEPTED",
                "Shop ownership transferred", "Your shop ownership transfer was accepted.",
                Map.of("shopId", shop.id(), "transferId", transferId));
        return requiredShop(shop.id());
    }

    @Transactional
    public void rejectOwnershipTransfer(UUID userId, UUID transferId) {
        OwnershipTransferResponse transfer = pendingTransfer(transferId, true);
        if (!transfer.toUserId().equals(userId)) throw transferNotFound();
        if (!shops.decideOwnershipTransfer(transferId, "REJECTED")) throw transferStateConflict();
        audit.record(userId, transfer.shopId(), "SHOP_OWNERSHIP_TRANSFER_REJECTED",
                "SHOP_OWNERSHIP_TRANSFER", transferId, Map.of());
        notifications.create(transfer.fromOwnerUserId(), "SHOP_OWNERSHIP_TRANSFER_REJECTED",
                "Shop ownership transfer rejected", "The proposed new owner rejected the transfer.",
                Map.of("shopId", transfer.shopId(), "transferId", transferId));
    }

    @Transactional
    public void cancelOwnershipTransfer(UUID userId, UUID transferId) {
        OwnershipTransferResponse snapshot = pendingTransfer(transferId, false);
        if (!snapshot.fromOwnerUserId().equals(userId)) throw transferNotFound();
        ShopResponse shop = shops.findForUpdate(snapshot.shopId()).orElseThrow(ShopService::shopNotFound);
        OwnershipTransferResponse transfer = pendingTransfer(transferId, true);
        if (!transfer.fromOwnerUserId().equals(userId) || !transfer.shopId().equals(shop.id())) throw transferNotFound();
        if (!shop.ownerUserId().equals(userId)) throw transferStateConflict();
        if (!shops.decideOwnershipTransfer(transferId, "CANCELLED")) throw transferStateConflict();
        audit.record(userId, transfer.shopId(), "SHOP_OWNERSHIP_TRANSFER_CANCELLED",
                "SHOP_OWNERSHIP_TRANSFER", transferId, Map.of());
        notifications.create(transfer.toUserId(), "SHOP_OWNERSHIP_TRANSFER_CANCELLED",
                "Shop ownership transfer cancelled", "The shop owner cancelled the transfer request.",
                Map.of("shopId", transfer.shopId(), "transferId", transferId));
    }

    @Transactional
    @PreAuthorize("@shopAuthorization.hasPermission(authentication, #shopId, 'shop.profile.update')")
    public void closeByOwner(UUID actorId, UUID shopId) {
        ShopResponse shop = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        if (!shop.ownerUserId().equals(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SHOP_OWNER_REQUIRED", "Only the owner can close the shop");
        }
        if (!shops.moderate(shopId, "CLOSE")) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_STATE_CONFLICT", "The shop cannot be closed in its current state");
        }
        audit.record(actorId, shopId, "SHOP_CLOSED_BY_OWNER", "SHOP", shopId,
                Map.of("from", shop.status(), "to", "CLOSED"));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'shop.suspend')")
    public SliceResponse<ShopResponse> moderationQueue(String status, int limit, int offset) {
        validatePage(limit, offset);
        String normalized = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (normalized != null && !SHOP_STATUSES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHOP_STATUS_INVALID", "Invalid shop status");
        }
        return slice(shops.findAll(normalized, limit + 1, offset), limit, offset);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'shop.suspend')")
    public ShopResponse shopForModeration(UUID shopId) {
        return requiredShop(shopId);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasPermission(authentication, 'shop.suspend')")
    public ShopResponse moderate(UUID actorId, UUID shopId, ModerateShopRequest request) {
        ShopResponse before = shops.findForUpdate(shopId).orElseThrow(ShopService::shopNotFound);
        if (!shops.moderate(shopId, request.action().name())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_STATE_CONFLICT",
                    "The requested transition is not valid for the current shop state");
        }
        audit.record(actorId, shopId, "SHOP_" + request.action().name(), "SHOP", shopId,
                Map.of("from", before.status(), "action", request.action().name(), "reason", request.reason().strip()));
        String moderationAction = switch (request.action()) {
            case SUSPEND -> "SUSPEND";
            case REACTIVATE -> "RESTORE";
            case CLOSE -> "CLOSE";
        };
        String newStatus = switch (request.action()) {
            case SUSPEND -> "SUSPENDED";
            case REACTIVATE -> "ACTIVE";
            case CLOSE -> "CLOSED";
        };
        moderation.shopDecision(actorId, shopId, moderationAction, request.reason().strip(), before.status(), newStatus);
        notifications.create(before.ownerUserId(), "SHOP_" + request.action().name(), "Shop status changed",
                "The status of " + before.name() + " was changed by platform moderation.",
                Map.of("shopId", shopId, "action", request.action().name(), "reason", request.reason().strip()));
        return requiredShop(shopId);
    }

    private List<String> validatedAssignableRoles(Collection<String> requested) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String role : requested) normalized.add(role.strip().toUpperCase(Locale.ROOT));
        if (normalized.contains("SHOP_OWNER")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHOP_OWNER_ROLE_FORBIDDEN",
                    "The owner role can only be assigned by ownership transfer");
        }
        Map<String, UUID> known = shops.shopRoles();
        if (normalized.isEmpty() || !known.keySet().containsAll(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHOP_ROLE_INVALID", "One or more shop roles are invalid");
        }
        return List.copyOf(normalized);
    }

    private List<UUID> roleIds(Collection<String> roles) {
        Map<String, UUID> known = shops.shopRoles();
        return roles.stream().map(known::get).toList();
    }

    private ShopResponse requiredShop(UUID shopId) {
        return shops.find(shopId).orElseThrow(ShopService::shopNotFound);
    }

    private OwnershipTransferResponse pendingTransfer(UUID transferId, boolean lock) {
        OwnershipTransferResponse transfer = (lock
                ? shops.ownershipTransferForUpdate(transferId)
                : shops.ownershipTransfer(transferId)).orElseThrow(ShopService::transferNotFound);
        if (!"PENDING".equals(transfer.status())) throw transferStateConflict();
        if (!transfer.expiresAt().isAfter(clock.instant())) {
            throw new ApiException(HttpStatus.GONE, "SHOP_TRANSFER_EXPIRED", "The ownership transfer has expired");
        }
        return transfer;
    }

    private static void requireActive(ShopResponse shop) {
        if (!"ACTIVE".equals(shop.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SHOP_NOT_ACTIVE", "The shop is not active");
        }
    }

    private static UUID requiredRole(Map<String, UUID> roles, String code) {
        UUID id = roles.get(code);
        if (id == null) throw new IllegalStateException("Missing required shop role: " + code);
        return id;
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

    private static ApiException shopNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND", "Shop not found");
    }

    private static ApiException memberNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SHOP_MEMBER_NOT_FOUND", "Shop member not found");
    }

    private static ApiException invitationNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SHOP_INVITATION_NOT_FOUND", "Shop invitation not found");
    }

    private static ApiException transferNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SHOP_TRANSFER_NOT_FOUND", "Ownership transfer not found");
    }

    private static ApiException transferStateConflict() {
        return new ApiException(HttpStatus.CONFLICT, "SHOP_TRANSFER_STATE_CONFLICT",
                "The ownership transfer is no longer pending");
    }
}
