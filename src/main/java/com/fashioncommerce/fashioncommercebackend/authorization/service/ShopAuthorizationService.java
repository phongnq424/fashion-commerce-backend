package com.fashioncommerce.fashioncommercebackend.authorization.service;

import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service("shopAuthorization")
@Transactional(readOnly = true)
public class ShopAuthorizationService {
    private final AuthorizationRepository repository;
    public ShopAuthorizationService(AuthorizationRepository repository) { this.repository = repository; }

    /** For shop-level actions only. Resource actions must resolve the persisted shop as below. */
    public boolean hasPermission(Authentication authentication, UUID shopId, String permission) {
        return shopId != null && permission != null && CurrentIdentity.userId(authentication)
                .filter(id -> repository.hasShopPermission(id, shopId, permission)).isPresent();
    }

    public boolean canUpdateProduct(Authentication authentication, UUID productId) {
        return productId != null && repository.productShop(productId)
                .filter(shop -> hasPermission(authentication, shop, "shop.product.update")).isPresent();
    }

    public boolean canProcessOrder(Authentication authentication, UUID shopOrderId) {
        return shopOrderId != null && repository.orderShop(shopOrderId)
                .filter(shop -> hasPermission(authentication, shop, "shop.orders.process")).isPresent();
    }
}
