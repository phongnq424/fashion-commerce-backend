package com.fashioncommerce.fashioncommercebackend;

import com.fashioncommerce.fashioncommercebackend.auth.dto.*;
import com.fashioncommerce.fashioncommercebackend.auth.service.*;
import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.authorization.service.*;
import com.fashioncommerce.fashioncommercebackend.integration.IsolatedDatabaseInitializer;
import com.fashioncommerce.fashioncommercebackend.user.repository.*;
import com.fashioncommerce.fashioncommercebackend.user.service.CurrentUserService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FC_RUN_POSTGRES_TESTS", matches = "ISOLATED_DATABASE_ONLY")
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:postgresql://${TEST_DB_HOST}/${TEST_DB_NAME}?sslmode=require",
        "spring.datasource.username=${TEST_DB_USERNAME}", "spring.datasource.password=${TEST_DB_PASSWORD}",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate"
})
@ContextConfiguration(initializers = IsolatedDatabaseInitializer.class)
@Transactional
class FashionCommerceBackendApplicationTests {
    @Autowired AuthenticationService auth;
    @Autowired AuthorizationRepository authorization;
    @Autowired PlatformAuthorizationService platform;
    @Autowired ShopAuthorizationService shops;
    @Autowired CurrentUserService currentUser;
    @Autowired UserRepository users;
    @Autowired UserCredentialRepository credentials;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtDecoder decoder;
    @Autowired PlatformTransactionManager transactions;

    @DynamicPropertySource static void key(DynamicPropertyRegistry properties) {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String secret = Base64.getEncoder().encodeToString(bytes);
        properties.add("security.jwt.secret-base64", () -> secret);
    }
    private RegisterRequest registration(String email) { return new RegisterRequest(email, "StrongPassword123", "Integration User", null); }
    private UserResponse newUser() { return auth.register(registration("it-" + UUID.randomUUID() + "@example.com")); }
    private TokenResponse login(UserResponse user) { return auth.login(new LoginRequest(user.email(), "StrongPassword123")); }
    private JwtAuthenticationToken identity(UserResponse user) { return new JwtAuthenticationToken(decoder.decode(login(user).accessToken()), List.of()); }
    private void grant(UUID user, String role) {
        jdbc.update("insert into user_platform_roles(user_id, role_id) select ?, id from roles where code=?", user, role);
    }

    @Test void contextLoadsWithAllMigrationsAndValidatedEntityMappings() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version in ('1','1.1','2','3','4','5')", Integer.class)).isEqualTo(6);
    }
    @Test void registrationPersistsCredentialAndCustomerButNoVerifiedEmail() {
        UserResponse user = newUser();
        assertThat(users.findById(user.id()).orElseThrow().getEmailVerifiedAt()).isNull();
        String hash = credentials.findById(user.id()).orElseThrow().getPasswordHash();
        assertThat(passwords.matches("StrongPassword123", hash)).isTrue();
        assertThat(authorization.platformRoles(user.id())).containsExactly("CUSTOMER");
    }
    @Test void databaseCaseInsensitiveUniqueIndexRejectsDuplicate() {
        UserResponse user = newUser();
        // Bypass the service pre-check to exercise the real PostgreSQL unique constraint.
        assertThatThrownBy(() -> jdbc.update("insert into users(email, display_name) values(?, 'Duplicate')", user.email().toUpperCase(Locale.ROOT)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void failedCredentialChangesSurviveRejectedLogin() {
        UserResponse user = newUser();
        assertThatThrownBy(() -> auth.login(new LoginRequest(user.email(), "wrong"))).isInstanceOf(InvalidCredentialsException.class);
        assertThat(credentials.findById(user.id()).orElseThrow().getFailedAttempts()).isEqualTo(1);
    }
    @Test void refreshRotationLogoutAndOpaqueHashPersistence() {
        UserResponse user = newUser(); TokenResponse first = login(user);
        String stored = jdbc.queryForObject("select refresh_token_hash from user_sessions where user_id=?", String.class, user.id());
        assertThat(stored).hasSize(64).isNotEqualTo(first.refreshToken());
        TokenResponse rotated = auth.refresh(new RefreshRequest(first.refreshToken()));
        assertThat(rotated.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(rotated.refreshExpiresAt()).isEqualTo(first.refreshExpiresAt());
        assertThatThrownBy(() -> auth.refresh(new RefreshRequest(first.refreshToken()))).isInstanceOf(com.fashioncommerce.fashioncommercebackend.common.exception.ApiException.class);
        auth.logout(user.id(), new RefreshRequest(rotated.refreshToken()));
        assertThatThrownBy(() -> auth.refresh(new RefreshRequest(rotated.refreshToken()))).isInstanceOf(com.fashioncommerce.fashioncommercebackend.common.exception.ApiException.class);
        // Logout does not claim to revoke the independently signed access token.
        assertThat(decoder.decode(first.accessToken()).getSubject()).isEqualTo(user.id().toString());
    }
    @Test void inactiveUserCannotRefreshOrLogin() {
        UserResponse user = newUser(); TokenResponse token = login(user);
        jdbc.update("update users set status='SUSPENDED' where id=?", user.id());
        // Clear the first-level persistence context so the lock reads the changed status.
        entityManager.clear();
        assertThatThrownBy(() -> auth.refresh(new RefreshRequest(token.refreshToken()))).isInstanceOf(com.fashioncommerce.fashioncommercebackend.common.exception.ApiException.class);
        assertThatThrownBy(() -> login(user)).isInstanceOf(InvalidCredentialsException.class);
    }
    @Test void expiredSessionCannotRefresh() {
        UserResponse user = newUser(); TokenResponse token = login(user);
        jdbc.update("update user_sessions set created_at=now()-interval '2 days', expires_at=now()-interval '1 day' where user_id=?", user.id());
        entityManager.clear();
        assertThatThrownBy(() -> auth.refresh(new RefreshRequest(token.refreshToken()))).isInstanceOf(com.fashioncommerce.fashioncommercebackend.common.exception.ApiException.class);
    }
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

    @Test void platformPermissionsAndFeatureActivationTakeEffectImmediately() {
        UserResponse user = newUser(); var identity = identity(user);
        assertThat(platform.hasPermission(identity, "seller.review")).isFalse();
        grant(user.id(), "PLATFORM_MODERATOR");
        assertThat(platform.hasPermission(identity, "seller.review")).isTrue();
        assertThat(platform.hasPermission(identity, "platform.roles.manage")).isFalse();
        jdbc.update("delete from role_permissions where role_id=(select id from roles where code='PLATFORM_MODERATOR') and permission_id=(select id from permissions where code='seller.review')");
        assertThat(platform.hasPermission(identity, "seller.review")).isFalse();
        jdbc.update("update features set is_active=false where code='ACCOUNT'");
        assertThat(platform.hasOwnPermission(identity, "account.read_own", user.id())).isFalse();
    }
    @Test void ownPermissionsDoNotReadAnotherUser() {
        UserResponse user = newUser(); var identity = identity(user);
        assertThat(platform.hasOwnPermission(identity, "account.read_own", user.id())).isTrue();
        assertThat(platform.hasOwnPermission(identity, "account.read_own", newUser().id())).isFalse();
    }
    private UUID newShop() {
        UserResponse owner = newUser(); UUID application = UUID.randomUUID(), shop = UUID.randomUUID();
        jdbc.update("""
                insert into seller_applications(id, applicant_user_id, proposed_shop_name, contact_email, contact_phone, legal_name, status)
                values(?, ?, 'IT Shop', ?, '0901234567', 'IT Owner', 'APPROVED')
                """, application, owner.id(), owner.email());
        jdbc.update("""
                insert into shops(id, owner_user_id, application_id, name, slug, contact_email, contact_phone, pickup_address)
                values(?, ?, ?, 'IT Shop', ?, ?, '0901234567', '{}'::jsonb)
                """, shop, owner.id(), application, "it-" + shop, owner.email());
        return shop;
    }
    private UUID joinShop(UUID user, UUID shop) {
        UUID member = UUID.randomUUID();
        jdbc.update("insert into shop_members(id, shop_id, user_id, status) values(?, ?, ?, 'ACTIVE')", member, shop, user);
        jdbc.update("insert into shop_member_roles(member_id, role_id) select ?, id from roles where code='SHOP_ORDERS'", member);
        return member;
    }
    @Test void shopIsolationMembershipStatusShopStatusAndNoAdminBypass() {
        UserResponse user = newUser(); var identity = identity(user); UUID a = newShop(), b = newShop();
        UUID member = joinShop(user.id(), a);
        assertThat(shops.hasPermission(identity, a, "shop.orders.process")).isTrue();
        assertThat(shops.hasPermission(identity, b, "shop.orders.process")).isFalse();
        assertThat(platform.hasPermission(identity, "seller.review")).isFalse();
        jdbc.update("update shop_members set status='DISABLED' where id=?", member);
        assertThat(shops.hasPermission(identity, a, "shop.orders.process")).isFalse();
        jdbc.update("update shop_members set status='ACTIVE' where id=?", member);
        jdbc.update("update shops set status='SUSPENDED' where id=?", a);
        assertThat(shops.hasPermission(identity, a, "shop.orders.process")).isFalse();
        grant(user.id(), "PLATFORM_ADMIN");
        assertThat(shops.hasPermission(identity, b, "shop.orders.process")).isFalse();
    }

    @Test void resourceChecksResolvePersistedShopAndBuyer() {
        UserResponse user = newUser(), otherBuyer = newUser(); var identity = identity(user);
        UUID a = newShop(), b = newShop(); joinShop(user.id(), a);
        UUID order = UUID.randomUUID(), orderA = UUID.randomUUID(), orderB = UUID.randomUUID();
        jdbc.update("""
                insert into orders(id, order_number, buyer_id, recipient_snapshot, payment_method,
                  items_subtotal_minor, shipping_fee_minor, discount_total_minor, grand_total_minor)
                values(?, ?, ?, '{}'::jsonb, 'COD', 0, 0, 0, 0)
                """, order, "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 28), otherBuyer.id());
        for (var pair : List.of(Map.entry(orderA, a), Map.entry(orderB, b))) {
            jdbc.update("""
                    insert into shop_orders(id, order_id, shop_id, shop_order_number,
                      items_subtotal_minor, shipping_fee_minor, discount_total_minor, total_minor)
                    values(?, ?, ?, ?, 0, 0, 0, 0)
                    """, pair.getKey(), order, pair.getValue(), pair.getKey().toString());
        }
        assertThat(shops.canProcessOrder(identity, orderA)).isTrue();
        assertThat(shops.canProcessOrder(identity, orderB)).isFalse();
        assertThat(platform.canReadOrder(identity, order)).isFalse();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRegistrationCreatesExactlyOneAccount() throws Exception {
        String email = "it-race-" + UUID.randomUUID() + "@example.com";
        try {
            List<Boolean> outcomes = race(() -> auth.register(registration(email)), () -> auth.register(registration(email.toUpperCase(Locale.ROOT))));
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            assertThat(jdbc.queryForObject("select count(*) from users where lower(email)=?", Integer.class, email)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from user_credentials c join users u on u.id=c.user_id where u.email=?", Integer.class, email)).isEqualTo(1);
        } finally { jdbc.update("delete from users where lower(email)=?", email); }
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRefreshAllowsOneRequestAndRejectedLoginCommitsCounter() throws Exception {
        UserResponse user = newUser();
        try {
            assertThatThrownBy(() -> auth.login(new LoginRequest(user.email(), "wrong"))).isInstanceOf(InvalidCredentialsException.class);
            assertThat(jdbc.queryForObject("select failed_attempts from user_credentials where user_id=?", Integer.class, user.id())).isEqualTo(1);
            TokenResponse token = login(user);
            List<Boolean> outcomes = race(() -> auth.refresh(new RefreshRequest(token.refreshToken())), () -> auth.refresh(new RefreshRequest(token.refreshToken())));
            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        } finally { jdbc.update("delete from users where id=?", user.id()); }
    }
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rollbackRemovesUserCredentialAndRoleTogether() {
        String email = "it-rollback-" + UUID.randomUUID() + "@example.com";
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        UUID userId = transaction.execute(status -> {
            UserResponse user = auth.register(registration(email));
            assertThat(credentials.findById(user.id())).isPresent();
            assertThat(authorization.platformRoles(user.id())).containsExactly("CUSTOMER");
            status.setRollbackOnly();
            return user.id();
        });
        assertThat(jdbc.queryForObject("select count(*) from users where id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_credentials where user_id=?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_platform_roles where user_id=?", Integer.class, userId)).isZero();
    }
    private List<Boolean> race(Supplier<?> a, Supplier<?> b) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Supplier<?> action : List.of(a, b)) futures.add(executor.submit(() -> {
                ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race start timed out");
                try { action.get(); return true; }
                catch (com.fashioncommerce.fashioncommercebackend.common.exception.ApiException | org.springframework.dao.DataIntegrityViolationException ex) { return false; }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
