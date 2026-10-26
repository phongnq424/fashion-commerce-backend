package com.fashioncommerce.fashioncommercebackend.authorization.service;

import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service("platformAuthorization")
@Transactional(readOnly = true)
public class PlatformAuthorizationService {
    private final AuthorizationRepository repository;
    public PlatformAuthorizationService(AuthorizationRepository repository) { this.repository = repository; }

    public boolean hasPermission(Authentication authentication, String permission) {
        // Own-scoped capabilities must use an ownership-aware check.
        return permission != null && !permission.endsWith("_own") && check(authentication, permission);
    }

    public boolean hasOwnPermission(Authentication authentication, String permission, UUID actualOwnerId) {
        return permission != null && permission.endsWith("_own") && CurrentIdentity.userId(authentication)
                .filter(id -> id.equals(actualOwnerId)).filter(id -> repository.hasPlatformPermission(id, permission)).isPresent();
    }

    /** Order owner is resolved from the database, never from a client-supplied buyer ID. */
    public boolean canReadOrder(Authentication authentication, UUID orderId) {
        return orderId != null && repository.orderBuyer(orderId)
                .filter(owner -> hasOwnPermission(authentication, "order.read_own", owner)).isPresent();
    }

    private boolean check(Authentication authentication, String permission) {
        return CurrentIdentity.userId(authentication).filter(id -> repository.hasPlatformPermission(id, permission)).isPresent();
    }
}
