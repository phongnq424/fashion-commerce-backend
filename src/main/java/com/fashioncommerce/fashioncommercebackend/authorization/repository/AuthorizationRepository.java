package com.fashioncommerce.fashioncommercebackend.authorization.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit relational queries preserve the schema's composite scope relationships without an entity graph. */
@Repository
public class AuthorizationRepository {
    private final JdbcTemplate jdbc;
    public AuthorizationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UUID> customerRoleId() {
        return jdbc.query("select id from roles where code = 'CUSTOMER' and scope = 'PLATFORM'",
                (rs, row) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    public void assignCustomer(UUID userId, UUID roleId) {
        jdbc.update("insert into user_platform_roles (user_id, role_id, scope) values (?, ?, 'PLATFORM')", userId, roleId);
    }

    public List<String> platformRoles(UUID userId) {
        return jdbc.queryForList("""
                select r.code from user_platform_roles upr
                join roles r on r.id = upr.role_id and r.scope = upr.scope
                where upr.user_id = ? and r.scope = 'PLATFORM' order by r.code
                """, String.class, userId);
    }

    public boolean hasPlatformPermission(UUID userId, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                  select 1 from users u
                  join user_platform_roles upr on upr.user_id = u.id
                  join roles r on r.id = upr.role_id and r.scope = upr.scope
                  join role_permissions rp on rp.role_id = r.id and rp.scope = r.scope
                  join permissions p on p.id = rp.permission_id and p.scope = rp.scope
                  join features f on f.id = p.feature_id and f.scope = p.scope
                  where u.id = ? and u.status = 'ACTIVE' and r.scope = 'PLATFORM'
                    and p.code = ? and f.is_active
                )
                """, Boolean.class, userId, permission));
    }

    public boolean hasShopPermission(UUID userId, UUID shopId, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                  select 1 from users u
                  join shop_members sm on sm.user_id = u.id
                  join shops s on s.id = sm.shop_id
                  join shop_member_roles smr on smr.member_id = sm.id
                  join roles r on r.id = smr.role_id and r.scope = smr.scope
                  join role_permissions rp on rp.role_id = r.id and rp.scope = r.scope
                  join permissions p on p.id = rp.permission_id and p.scope = rp.scope
                  join features f on f.id = p.feature_id and f.scope = p.scope
                  where u.id = ? and s.id = ? and u.status = 'ACTIVE' and s.status = 'ACTIVE'
                    and sm.status = 'ACTIVE' and r.scope = 'SHOP' and p.code = ? and f.is_active
                )
                """, Boolean.class, userId, shopId, permission));
    }

    public Optional<UUID> productShop(UUID productId) {
        return jdbc.query("select shop_id from products where id = ?", (rs, row) -> rs.getObject(1, UUID.class), productId).stream().findFirst();
    }
    public Optional<UUID> orderShop(UUID shopOrderId) {
        return jdbc.query("select shop_id from shop_orders where id = ?", (rs, row) -> rs.getObject(1, UUID.class), shopOrderId).stream().findFirst();
    }
    public Optional<UUID> orderBuyer(UUID orderId) {
        return jdbc.query("select buyer_id from orders where id = ?", (rs, row) -> rs.getObject(1, UUID.class), orderId).stream().findFirst();
    }
}
