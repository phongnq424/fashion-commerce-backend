package com.fashioncommerce.fashioncommercebackend;

import com.fashioncommerce.fashioncommercebackend.auth.dto.*;
import com.fashioncommerce.fashioncommercebackend.auth.service.*;
import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.authorization.service.*;
import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.repository.CommerceRepository;
import com.fashioncommerce.fashioncommercebackend.integration.IsolatedDatabaseInitializer;
import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.*;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository;
import com.fashioncommerce.fashioncommercebackend.security.jwt.RevokedAccessTokenRepository;
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
    @Autowired RevokedAccessTokenRepository revokedAccessTokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ProductRepository products;
    @Autowired CommerceRepository commerce;

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
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success and version in ('1','1.1','2','3','4','5','6','7','8','9','10','11','12','13','14','15')", Integer.class)).isEqualTo(16);
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
        Jwt access = decoder.decode(first.accessToken());
        UUID accessTokenId = UUID.fromString(access.getId());
        auth.logout(user.id(), accessTokenId, access.getExpiresAt(), new RefreshRequest(rotated.refreshToken()));
        assertThatThrownBy(() -> auth.refresh(new RefreshRequest(rotated.refreshToken()))).isInstanceOf(com.fashioncommerce.fashioncommercebackend.common.exception.ApiException.class);
        assertThat(revokedAccessTokens.isRevoked(accessTokenId, user.id())).isTrue();
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

    @Test void productVersionAndPublicCursorQueryUsePostgresSchema() {
        UUID shopId = newShop();
        UUID categoryId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        String sku = "IT-" + UUID.randomUUID().toString().substring(0, 8);
        CreateProductRequest create = new CreateProductRequest(categoryId, null, sku, "Integration Shirt",
                "Integration catalog product", "Cotton", "Regular", Gender.UNISEX, null, null, List.of(),
                List.of(new VariantRequest(sku, null, Map.of(), 199_000, 249_000L,
                        250, null, null, null, 0, 2)),
                List.of(new ImageRequest("integration/products/main.jpg", "Main", 0, true, null)));
        UUID productId = products.create(UUID.randomUUID(), shopId, create, "integration-" + productIdSuffix());
        assertThat(products.response(productId).version()).isZero();

        UpdateProductRequest update = new UpdateProductRequest(0L, null, null, null, "Updated Shirt",
                null, null, null, null, null, null);
        assertThat(products.update(productId, update)).isTrue();
        assertThat(products.update(productId, update)).isFalse();
        assertThat(products.publish(productId, 1L)).isTrue();

        var filter = new ProductRepository.CatalogFilter("Updated Shirt", categoryId, null, null,
                null, null, false, ProductSort.NEWEST);
        assertThat(products.activeProducts(filter, null, 2)).extracting(entry -> entry.product().id())
                .contains(productId);
    }

    @Test void cartAddressAndCheckoutSnapshotUsePostgresSchema() {
        UserResponse buyer = newUser();
        var cart = commerce.ensureCart(buyer.id());
        CreateAddressRequest addressRequest = new CreateAddressRequest("Integration Buyer", "0901234567",
                "1 Test Street", null, "Test Ward", "Test District", "Test Province", "01", null, "VN", true);
        UUID addressId = commerce.createAddress(buyer.id(), addressRequest, true);
        var address = commerce.address(buyer.id(), addressId).orElseThrow();
        RecipientSnapshot recipient = new RecipientSnapshot(address.recipientName(), address.recipientPhone(),
                address.addressLine1(), address.addressLine2(), address.ward(), address.district(),
                address.province(), address.provinceCode(), address.postalCode(), address.countryCode());
        CheckoutItemSnapshot item = new CheckoutItemSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Integration Product", "IT-SKU", Map.of("SIZE", "M"), "integration/main.jpg",
                "https://cdn.example/integration/main.jpg", 100_000, 2, 200_000);
        CheckoutShopSnapshot shop = new CheckoutShopSnapshot(UUID.randomUUID(), "Integration Shop", "integration-shop",
                200_000, 30_000, 230_000, List.of(item));
        UUID snapshotId = commerce.createCheckoutSnapshot(buyer.id(), cart.id(), cart.version(), recipient,
                List.of(shop), 200_000, 30_000, 230_000, Instant.now().plusSeconds(900));

        assertThat(jdbc.queryForObject("select status from checkout_snapshots where id=?", String.class, snapshotId))
                .isEqualTo("READY");
        assertThat(jdbc.queryForObject("select jsonb_array_length(items_snapshot) from checkout_snapshots where id=?",
                Integer.class, snapshotId)).isEqualTo(1);
    }

    private String productIdSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
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
