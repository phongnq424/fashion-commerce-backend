package com.fashioncommerce.fashioncommercebackend.shop.repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.fashioncommerce.fashioncommercebackend.shop.dto.ShopDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
public class ShopRepository {
    private static final String SHOP_COLUMNS = """
            s.id, s.owner_user_id, s.application_id, s.name, s.slug, s.description,
            s.logo_key, s.banner_key, s.contact_email, s.contact_phone, s.pickup_address,
            s.status, s.activated_at, s.suspended_at, s.created_at, s.updated_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ShopRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public ShopResponse create(UUID shopId, UUID ownerId, UUID applicationId, CreateRequest request) {
        jdbc.update("""
                insert into shops
                    (id, owner_user_id, application_id, name, slug, description, logo_key, banner_key,
                     contact_email, contact_phone, pickup_address)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, shopId, ownerId, applicationId, request.name().strip(), request.slug().strip().toLowerCase(Locale.ROOT),
                trimToNull(request.description()), trimToNull(request.logoKey()), trimToNull(request.bannerKey()),
                request.contactEmail().strip().toLowerCase(Locale.ROOT), request.contactPhone().strip(),
                json(request.pickupAddress().asMap()));
        jdbc.update("insert into shop_settings (shop_id) values (?)", shopId);
        return find(shopId).orElseThrow();
    }

    public Optional<ShopResponse> find(UUID shopId) {
        return jdbc.query("select " + SHOP_COLUMNS + " from shops s where s.id = ?", this::mapShop, shopId)
                .stream().findFirst();
    }

    public Optional<ShopResponse> findForUpdate(UUID shopId) {
        return jdbc.query("select " + SHOP_COLUMNS + " from shops s where s.id = ? for update", this::mapShop, shopId)
                .stream().findFirst();
    }

    public List<ShopResponse> findForUser(UUID userId, int limitPlusOne, int offset) {
        return jdbc.query("select " + SHOP_COLUMNS + " from shops s join shop_members sm on sm.shop_id = s.id " +
                        "where sm.user_id = ? and sm.status = 'ACTIVE' order by s.created_at desc limit ? offset ?",
                this::mapShop, userId, limitPlusOne, offset);
    }

    public List<ShopResponse> findAll(String status, int limitPlusOne, int offset) {
        if (status == null) {
            return jdbc.query("select " + SHOP_COLUMNS + " from shops s order by s.created_at desc limit ? offset ?",
                    this::mapShop, limitPlusOne, offset);
        }
        return jdbc.query("select " + SHOP_COLUMNS + " from shops s where s.status = ? " +
                        "order by s.created_at desc limit ? offset ?", this::mapShop, status, limitPlusOne, offset);
    }

    public void update(UUID shopId, UpdateRequest request) {
        String address = request.pickupAddress() == null ? null : json(request.pickupAddress().asMap());
        jdbc.update("""
                update shops set
                    name = coalesce(?, name), slug = coalesce(?, slug), description = coalesce(?, description),
                    logo_key = coalesce(?, logo_key), banner_key = coalesce(?, banner_key),
                    contact_email = coalesce(?, contact_email), contact_phone = coalesce(?, contact_phone),
                    pickup_address = coalesce(?::jsonb, pickup_address)
                where id = ?
                """, strip(request.name()), lower(request.slug()), strip(request.description()), strip(request.logoKey()),
                strip(request.bannerKey()), lower(request.contactEmail()), strip(request.contactPhone()), address, shopId);
    }

    public SettingsResponse settings(UUID shopId) {
        return jdbc.query("""
                select shop_id, handling_days, return_window_days, default_shipping_fee_minor,
                       allow_cod, auto_accept_orders from shop_settings where shop_id = ?
                """, (rs, row) -> new SettingsResponse(
                rs.getObject("shop_id", UUID.class), rs.getShort("handling_days"),
                rs.getShort("return_window_days"), rs.getLong("default_shipping_fee_minor"),
                rs.getBoolean("allow_cod"), rs.getBoolean("auto_accept_orders")), shopId).stream().findFirst().orElseThrow();
    }

    public void updateSettings(UUID shopId, UpdateSettingsRequest request) {
        jdbc.update("""
                update shop_settings set handling_days = ?, return_window_days = ?,
                    default_shipping_fee_minor = ?, allow_cod = ?, auto_accept_orders = ?
                where shop_id = ?
                """, request.handlingDays(), request.returnWindowDays(), request.defaultShippingFeeMinor(),
                request.allowCod(), request.autoAcceptOrders(), shopId);
    }

    public Optional<UserRow> activeUserByEmail(String email) {
        return jdbc.query("select id, email, display_name from users where lower(email) = lower(?) and status = 'ACTIVE'",
                (rs, row) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name")), email).stream().findFirst();
    }

    public Optional<MemberRow> memberForUpdate(UUID shopId, UUID userId) {
        return jdbc.query("""
                select id, shop_id, user_id, invited_by, status, joined_at, created_at
                from shop_members where shop_id = ? and user_id = ? for update
                """, this::mapMemberRow, shopId, userId).stream().findFirst();
    }

    public Optional<MemberRow> memberByIdForUpdate(UUID shopId, UUID memberId) {
        return jdbc.query("""
                select id, shop_id, user_id, invited_by, status, joined_at, created_at
                from shop_members where shop_id = ? and id = ? for update
                """, this::mapMemberRow, shopId, memberId).stream().findFirst();
    }

    public UUID createInvitation(UUID shopId, UUID userId, UUID invitedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into shop_members (id, shop_id, user_id, invited_by, status)
                values (?, ?, ?, ?, 'INVITED')
                """, id, shopId, userId, invitedBy);
        return id;
    }

    public UUID createActiveMember(UUID shopId, UUID userId, UUID invitedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into shop_members (id, shop_id, user_id, invited_by, status, joined_at)
                values (?, ?, ?, ?, 'ACTIVE', now())
                """, id, shopId, userId, invitedBy);
        return id;
    }

    public Optional<MemberRow> invitationForUpdate(UUID memberId, UUID userId) {
        return jdbc.query("""
                select id, shop_id, user_id, invited_by, status, joined_at, created_at
                from shop_members where id = ? and user_id = ? for update
                """, this::mapMemberRow, memberId, userId).stream().findFirst();
    }

    public Optional<MemberRow> invitation(UUID memberId, UUID userId) {
        return jdbc.query("""
                select id, shop_id, user_id, invited_by, status, joined_at, created_at
                from shop_members where id = ? and user_id = ?
                """, this::mapMemberRow, memberId, userId).stream().findFirst();
    }

    public void reinvite(UUID memberId, UUID invitedBy) {
        jdbc.update("""
                update shop_members set status = 'INVITED', invited_by = ?, joined_at = null
                where id = ?
                """, invitedBy, memberId);
    }

    public boolean acceptInvitation(UUID memberId, UUID userId) {
        return jdbc.update("""
                update shop_members set status = 'ACTIVE', joined_at = now()
                where id = ? and user_id = ? and status = 'INVITED'
                """, memberId, userId) == 1;
    }

    public boolean declineInvitation(UUID memberId, UUID userId) {
        return jdbc.update("""
                update shop_members set status = 'DECLINED'
                where id = ? and user_id = ? and status = 'INVITED'
                """, memberId, userId) == 1;
    }

    public void disableMember(UUID memberId) {
        jdbc.update("update shop_members set status = 'DISABLED' where id = ?", memberId);
    }

    public List<MemberResponse> members(UUID shopId, int limitPlusOne, int offset) {
        List<MemberResponse> members = jdbc.query("""
                select sm.id, sm.user_id, u.email, u.display_name, sm.status, sm.invited_by,
                       sm.joined_at, sm.created_at
                from shop_members sm join users u on u.id = sm.user_id
                where sm.shop_id = ? order by sm.created_at limit ? offset ?
                """, (rs, row) -> mapMemberResponse(rs, shopId), shopId, limitPlusOne, offset);
        return members;
    }

    public Optional<MemberResponse> member(UUID shopId, UUID memberId) {
        return jdbc.query("""
                select sm.id, sm.user_id, u.email, u.display_name, sm.status, sm.invited_by,
                       sm.joined_at, sm.created_at
                from shop_members sm join users u on u.id = sm.user_id
                where sm.shop_id = ? and sm.id = ?
                """, (rs, row) -> mapMemberResponse(rs, shopId), shopId, memberId).stream().findFirst();
    }

    public List<InvitationResponse> invitations(UUID userId) {
        return jdbc.query("""
                select sm.id, sm.shop_id, s.name, sm.invited_by, sm.created_at
                from shop_members sm join shops s on s.id = sm.shop_id
                where sm.user_id = ? and sm.status = 'INVITED' and s.status = 'ACTIVE'
                order by sm.created_at desc
                """, (rs, row) -> {
            UUID memberId = rs.getObject("id", UUID.class);
            return new InvitationResponse(memberId, rs.getObject("shop_id", UUID.class), rs.getString("name"),
                    rs.getObject("invited_by", UUID.class), instant(rs, "created_at"), roles(memberId));
        }, userId);
    }

    public Map<String, UUID> shopRoles() {
        Map<String, UUID> roles = new HashMap<>();
        jdbc.query("select id, code from roles where scope = 'SHOP'",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> roles.put(rs.getString("code"), rs.getObject("id", UUID.class)));
        return roles;
    }

    public List<RoleResponse> assignableShopRoles() {
        return jdbc.query("""
                select code, name, description from roles
                where scope = 'SHOP' and code <> 'SHOP_OWNER' order by code
                """, (rs, row) -> new RoleResponse(rs.getString("code"), rs.getString("name"),
                rs.getString("description")));
    }

    public void replaceRoles(UUID memberId, Collection<UUID> roleIds, UUID grantedBy) {
        jdbc.update("delete from shop_member_roles where member_id = ?", memberId);
        for (UUID roleId : roleIds) {
            jdbc.update("""
                    insert into shop_member_roles (member_id, role_id, scope, granted_by)
                    values (?, ?, 'SHOP', ?)
                    """, memberId, roleId, grantedBy);
        }
    }

    public void addRole(UUID memberId, UUID roleId, UUID grantedBy) {
        jdbc.update("""
                insert into shop_member_roles (member_id, role_id, scope, granted_by)
                values (?, ?, 'SHOP', ?) on conflict do nothing
                """, memberId, roleId, grantedBy);
    }

    public void removeRole(UUID memberId, UUID roleId) {
        jdbc.update("delete from shop_member_roles where member_id = ? and role_id = ?", memberId, roleId);
    }

    public void transferOwner(UUID shopId, UUID newOwnerId, UUID newOwnerApplicationId) {
        jdbc.update("update shops set owner_user_id = ?, application_id = ? where id = ?",
                newOwnerId, newOwnerApplicationId, shopId);
    }

    public OwnershipTransferResponse createOwnershipTransfer(UUID shopId, UUID fromOwnerId, UUID toUserId,
                                                              boolean keepPreviousOwnerAsManager) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into shop_ownership_transfers
                    (id, shop_id, from_owner_user_id, to_user_id, keep_previous_owner_as_manager, expires_at)
                values (?, ?, ?, ?, ?, now() + interval '7 days')
                """, id, shopId, fromOwnerId, toUserId, keepPreviousOwnerAsManager);
        return ownershipTransfer(id).orElseThrow();
    }

    public void expirePendingOwnershipTransfers(UUID shopId) {
        jdbc.update("""
                update shop_ownership_transfers set status = 'EXPIRED', decided_at = now()
                where shop_id = ? and status = 'PENDING' and expires_at <= now()
                """, shopId);
    }

    public Optional<OwnershipTransferResponse> ownershipTransfer(UUID transferId) {
        return jdbc.query("""
                select t.id, t.shop_id, s.name as shop_name, t.from_owner_user_id, t.to_user_id,
                       t.keep_previous_owner_as_manager, t.status, t.expires_at, t.decided_at, t.created_at
                from shop_ownership_transfers t join shops s on s.id = t.shop_id where t.id = ?
                """, this::mapTransfer, transferId).stream().findFirst();
    }

    public Optional<OwnershipTransferResponse> ownershipTransferForUpdate(UUID transferId) {
        return jdbc.query("""
                select t.id, t.shop_id, s.name as shop_name, t.from_owner_user_id, t.to_user_id,
                       t.keep_previous_owner_as_manager, t.status, t.expires_at, t.decided_at, t.created_at
                from shop_ownership_transfers t join shops s on s.id = t.shop_id where t.id = ? for update of t
                """, this::mapTransfer, transferId).stream().findFirst();
    }

    public List<OwnershipTransferResponse> ownershipTransfersForTarget(UUID userId) {
        return jdbc.query("""
                select t.id, t.shop_id, s.name as shop_name, t.from_owner_user_id, t.to_user_id,
                       t.keep_previous_owner_as_manager, t.status, t.expires_at, t.decided_at, t.created_at
                from shop_ownership_transfers t join shops s on s.id = t.shop_id
                where t.to_user_id = ? and t.status = 'PENDING' and t.expires_at > now()
                order by t.created_at desc
                """, this::mapTransfer, userId);
    }

    public List<OwnershipTransferResponse> ownershipTransfersForShop(UUID shopId) {
        return jdbc.query("""
                select t.id, t.shop_id, s.name as shop_name, t.from_owner_user_id, t.to_user_id,
                       t.keep_previous_owner_as_manager, t.status, t.expires_at, t.decided_at, t.created_at
                from shop_ownership_transfers t join shops s on s.id = t.shop_id
                where t.shop_id = ? order by t.created_at desc
                """, this::mapTransfer, shopId);
    }

    public boolean decideOwnershipTransfer(UUID transferId, String status) {
        return jdbc.update("""
                update shop_ownership_transfers set status = ?, decided_at = now()
                where id = ? and status = 'PENDING' and expires_at > now()
                """, status, transferId) == 1;
    }

    public boolean ownsShop(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from shops where owner_user_id = ?)",
                Boolean.class, userId));
    }

    public boolean moderate(UUID shopId, String action) {
        return switch (action) {
            case "SUSPEND" -> jdbc.update("""
                    update shops set status = 'SUSPENDED', suspended_at = now()
                    where id = ? and status = 'ACTIVE'
                    """, shopId) == 1;
            case "REACTIVATE" -> jdbc.update("""
                    update shops set status = 'ACTIVE', suspended_at = null
                    where id = ? and status = 'SUSPENDED'
                    """, shopId) == 1;
            case "CLOSE" -> jdbc.update("""
                    update shops set status = 'CLOSED'
                    where id = ? and status in ('ACTIVE', 'SUSPENDED')
                    """, shopId) == 1;
            default -> false;
        };
    }

    private MemberResponse mapMemberResponse(ResultSet rs, UUID shopId) throws SQLException {
        UUID memberId = rs.getObject("id", UUID.class);
        return new MemberResponse(memberId, rs.getObject("user_id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("status"), rs.getObject("invited_by", UUID.class),
                instant(rs, "joined_at"), instant(rs, "created_at"), roles(memberId));
    }

    private List<String> roles(UUID memberId) {
        return jdbc.queryForList("""
                select r.code from shop_member_roles smr join roles r on r.id = smr.role_id
                where smr.member_id = ? and r.scope = 'SHOP' order by r.code
                """, String.class, memberId);
    }

    private ShopResponse mapShop(ResultSet rs, int row) throws SQLException {
        return new ShopResponse(rs.getObject("id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                rs.getObject("application_id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getString("description"), rs.getString("logo_key"), rs.getString("banner_key"),
                rs.getString("contact_email"), rs.getString("contact_phone"), parseJson(rs.getString("pickup_address")),
                rs.getString("status"), instant(rs, "activated_at"), instant(rs, "suspended_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private MemberRow mapMemberRow(ResultSet rs, int row) throws SQLException {
        return new MemberRow(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("invited_by", UUID.class), rs.getString("status"),
                instant(rs, "joined_at"), instant(rs, "created_at"));
    }

    private OwnershipTransferResponse mapTransfer(ResultSet rs, int row) throws SQLException {
        return new OwnershipTransferResponse(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getString("shop_name"), rs.getObject("from_owner_user_id", UUID.class),
                rs.getObject("to_user_id", UUID.class), rs.getBoolean("keep_previous_owner_as_manager"),
                rs.getString("status"), instant(rs, "expires_at"), instant(rs, "decided_at"),
                instant(rs, "created_at"));
    }

    private Map<String, Object> parseJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        }
        catch (JacksonException ex) {
            throw new IllegalStateException("Invalid pickup address JSON in database", ex);
        }
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JacksonException ex) {
            throw new IllegalArgumentException("Pickup address is not serializable", ex);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static String lower(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    public record UserRow(UUID id, String email, String displayName) { }
    public record MemberRow(UUID id, UUID shopId, UUID userId, UUID invitedBy, String status,
                            Instant joinedAt, Instant createdAt) { }
}
