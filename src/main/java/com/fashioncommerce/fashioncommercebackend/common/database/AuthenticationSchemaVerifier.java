package com.fashioncommerce.fashioncommercebackend.common.database;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Read-only startup checks supplement Hibernate validation for tables accessed using SQL. */
@Component
@DependsOnDatabaseInitialization
public class AuthenticationSchemaVerifier implements InitializingBean {
    private final JdbcTemplate jdbc;
    public AuthenticationSchemaVerifier(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void afterPropertiesSet() {
        Map<String, List<String>> required = Map.ofEntries(
                Map.entry("users", List.of("id", "email", "phone", "status", "email_verified_at")),
                Map.entry("user_credentials", List.of("user_id", "password_hash", "failed_attempts", "locked_until")),
                Map.entry("user_sessions", List.of("id", "user_id", "refresh_token_hash", "expires_at", "revoked_at", "last_used_at")),
                Map.entry("user_verification_tokens", List.of("id", "token_hash", "purpose", "expires_at")),
                Map.entry("features", List.of("id", "scope", "is_active")),
                Map.entry("permissions", List.of("id", "feature_id", "scope", "code")),
                Map.entry("roles", List.of("id", "scope", "code")),
                Map.entry("role_permissions", List.of("role_id", "permission_id", "scope")),
                Map.entry("user_platform_roles", List.of("user_id", "role_id", "scope")),
                Map.entry("seller_applications", List.of("id", "applicant_user_id", "status", "reviewed_by", "reviewed_at")),
                Map.entry("shops", List.of("id", "owner_user_id", "application_id", "slug", "status")),
                Map.entry("shop_members", List.of("id", "user_id", "shop_id", "status")),
                Map.entry("shop_member_roles", List.of("member_id", "role_id", "scope")),
                Map.entry("shop_settings", List.of("shop_id", "handling_days", "return_window_days", "default_shipping_fee_minor")),
                Map.entry("shop_ownership_transfers", List.of("id", "shop_id", "from_owner_user_id", "to_user_id", "status", "expires_at")),
                Map.entry("seller_onboardings", List.of("id", "applicant_user_id", "shop_id", "status", "submitted_at", "reviewed_by", "reviewed_at", "completed_at")),
                Map.entry("shop_pickup_addresses", List.of("id", "shop_id", "contact_name", "province_name", "ward_name", "is_default_pickup")),
                Map.entry("shipping_providers", List.of("id", "code", "name", "is_active")),
                Map.entry("shop_shipping_configs", List.of("shop_id", "provider_id", "pickup_address_id", "is_enabled")),
                Map.entry("seller_tax_profiles", List.of("onboarding_id", "business_type", "tax_code", "status")),
                Map.entry("seller_identity_verifications", List.of("onboarding_id", "document_type", "document_number_last4", "status")),
                Map.entry("seller_policy_versions", List.of("version", "title", "is_required", "is_active", "effective_at")),
                Map.entry("seller_policy_acceptances", List.of("id", "onboarding_id", "policy_version", "accepted_at")),
                Map.entry("audit_logs", List.of("id", "actor_user_id", "action", "changes")),
                Map.entry("products", List.of("id", "shop_id")),
                Map.entry("product_variants", List.of("id", "product_id", "shop_id", "seller_sku", "price_minor")),
                Map.entry("product_images", List.of("id", "product_id", "storage_key", "is_primary")),
                Map.entry("inventory_balances", List.of("variant_id", "shop_id", "quantity_on_hand", "quantity_reserved")),
                Map.entry("product_moderation_cases", List.of("id", "product_id", "reporter_user_id", "reason_code", "status")),
                Map.entry("shop_orders", List.of("id", "shop_id")),
                Map.entry("orders", List.of("id", "buyer_id")));
        List<String> issues = new ArrayList<>();
        required.forEach((table, columns) -> {
            List<String> actual = jdbc.queryForList("select column_name from information_schema.columns where table_schema = current_schema() and table_name = ?", String.class, table);
            columns.stream().filter(column -> !actual.contains(column)).forEach(column -> issues.add(table + "." + column));
        });
        if (!issues.isEmpty()) throw new IllegalStateException("Missing authentication schema tables/columns: " + issues);
        if (jdbc.queryForObject("select to_regclass(current_schema() || '.flyway_schema_history') is not null", Boolean.class) != Boolean.TRUE) {
            throw new IllegalStateException("Missing flyway_schema_history; use a reviewed test migration environment");
        }
        List<String> applied = jdbc.queryForList("select version from flyway_schema_history where success", String.class);
        List<String> missing = new ArrayList<>(List.of("1", "1.1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12")); missing.removeAll(applied);
        if (!missing.isEmpty()) throw new IllegalStateException("Missing successful Flyway migrations: " + missing);
        if (jdbc.queryForObject("select count(*) from flyway_schema_history where not success", Integer.class) != 0) {
            throw new IllegalStateException("Failed entries in flyway_schema_history require review");
        }
        Map<String, String> definitions = new HashMap<>();
        jdbc.query("select indexname, indexdef from pg_indexes where schemaname = current_schema() and indexname in ('ix_users_3', 'ix_users_4', 'uq_user_sessions_refresh_token_hash')",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> definitions.put(rs.getString(1), rs.getString(2).toLowerCase(Locale.ROOT)));
        requireIndex(definitions, "ix_users_3", "lower(", "email");
        requireIndex(definitions, "ix_users_4", "phone", "where", "is not null");
        requireIndex(definitions, "uq_user_sessions_refresh_token_hash", "refresh_token_hash");
        List<String> constraints = jdbc.queryForList("select conname from pg_constraint where connamespace = current_schema()::regnamespace and convalidated", String.class);
        List<String> requiredConstraints = new ArrayList<>(List.of(
                "fk_permissions_feature_id_scope", "fk_role_permissions_role_id_scope", "fk_role_permissions_permission_id_scope",
                "fk_user_platform_roles_role_id_scope", "fk_shop_member_roles_role_id_scope", "fk_shop_member_roles_member_id",
                "fk_shop_members_shop_id", "fk_shop_members_user_id", "fk_user_credentials_user_id", "fk_user_sessions_user_id",
                "fk_shops_application_id", "ck_user_platform_roles_rule_1", "ck_shop_member_roles_rule_1"));
        requiredConstraints.removeAll(constraints);
        if (!requiredConstraints.isEmpty()) throw new IllegalStateException("Missing validated authentication constraints: " + requiredConstraints);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from roles where code='CUSTOMER' and scope='PLATFORM')", Boolean.class))) {
            throw new IllegalStateException("Missing PLATFORM CUSTOMER seed role");
        }
    }

    private void requireIndex(Map<String, String> definitions, String name, String... fragments) {
        String definition = definitions.getOrDefault(name, "");
        if (!definition.startsWith("create unique index") || Arrays.stream(fragments).anyMatch(fragment -> !definition.contains(fragment))) {
            throw new IllegalStateException("Missing/incompatible authentication unique index: " + name);
        }
    }
}
