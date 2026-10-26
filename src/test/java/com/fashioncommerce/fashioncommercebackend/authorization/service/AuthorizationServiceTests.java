package com.fashioncommerce.fashioncommercebackend.authorization.service;

import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationServiceTests {
    private final AuthorizationRepository repository = mock(AuthorizationRepository.class);
    private final PlatformAuthorizationService platform = new PlatformAuthorizationService(repository);
    private final ShopAuthorizationService shop = new ShopAuthorizationService(repository);
    private final UUID user = UUID.randomUUID();
    private final JwtAuthenticationToken identity = new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg", "HS256").subject(user.toString()).build(), List.of());

    @Test void missingPermissionAndUnknownIdentityDenyByDefault() {
        assertThat(platform.hasPermission(identity, "seller.review")).isFalse();
        assertThat(platform.hasPermission(null, "seller.review")).isFalse();
        assertThat(platform.hasPermission(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()), "seller.review")).isFalse();
        verify(repository, times(1)).hasPlatformPermission(user, "seller.review");
    }
    @Test void permissionsAreReadAgainWithoutCaching() {
        when(repository.hasPlatformPermission(user, "seller.review")).thenReturn(true, false);
        assertThat(platform.hasPermission(identity, "seller.review")).isTrue();
        assertThat(platform.hasPermission(identity, "seller.review")).isFalse();
    }
    @Test void ownPermissionNeedsMatchingPersistedOwner() {
        when(repository.hasPlatformPermission(user, "account.read_own")).thenReturn(true);
        assertThat(platform.hasOwnPermission(identity, "account.read_own", user)).isTrue();
        assertThat(platform.hasOwnPermission(identity, "account.read_own", UUID.randomUUID())).isFalse();
        assertThat(platform.hasPermission(identity, "account.read_own")).isFalse();
        assertThat(platform.hasOwnPermission(identity, "seller.review", user)).isFalse();
    }
    @Test void buyerOrderOwnershipComesFromDatabase() {
        UUID order = UUID.randomUUID();
        when(repository.orderBuyer(order)).thenReturn(Optional.of(UUID.randomUUID()));
        assertThat(platform.canReadOrder(identity, order)).isFalse();
        when(repository.orderBuyer(order)).thenReturn(Optional.of(user));
        when(repository.hasPlatformPermission(user, "order.read_own")).thenReturn(true);
        assertThat(platform.canReadOrder(identity, order)).isTrue();
    }
    @Test void productCannotUsePermissionInAnotherShop() {
        UUID product = UUID.randomUUID(), shopA = UUID.randomUUID(), shopB = UUID.randomUUID();
        when(repository.hasShopPermission(user, shopA, "shop.product.update")).thenReturn(true);
        when(repository.productShop(product)).thenReturn(Optional.of(shopB));
        assertThat(shop.hasPermission(identity, shopA, "shop.product.update")).isTrue();
        assertThat(shop.canUpdateProduct(identity, product)).isFalse();
        verify(repository).hasShopPermission(user, shopB, "shop.product.update");
    }
    @Test void shopOrderResolvesItsOwnShopAndMissingResourcesDeny() {
        UUID order = UUID.randomUUID(), shopId = UUID.randomUUID();
        when(repository.orderShop(order)).thenReturn(Optional.of(shopId));
        when(repository.hasShopPermission(user, shopId, "shop.orders.process")).thenReturn(true);
        assertThat(shop.canProcessOrder(identity, order)).isTrue();
        assertThat(shop.canProcessOrder(identity, UUID.randomUUID())).isFalse();
        assertThat(shop.hasPermission(null, shopId, "shop.orders.process")).isFalse();
    }
}
