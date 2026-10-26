package com.fashioncommerce.fashioncommercebackend.user.service;

import com.fashioncommerce.fashioncommercebackend.auth.dto.UserResponse;
import com.fashioncommerce.fashioncommercebackend.authorization.repository.AuthorizationRepository;
import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class CurrentUserService {
    private final UserRepository users;
    private final AuthorizationRepository authorization;
    public CurrentUserService(UserRepository users, AuthorizationRepository authorization) { this.users = users; this.authorization = authorization; }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'account.read_own', #userId)")
    public UserResponse currentUser(UUID userId) {
        var user = users.findById(userId).filter(u -> u.isActive())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "Authentication required"));
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getStatus(), user.getEmailVerifiedAt(), authorization.platformRoles(userId));
    }
}
