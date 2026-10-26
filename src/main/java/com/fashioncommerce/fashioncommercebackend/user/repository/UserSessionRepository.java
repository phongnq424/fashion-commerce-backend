package com.fashioncommerce.fashioncommercebackend.user.repository;

import com.fashioncommerce.fashioncommercebackend.user.entity.UserSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserSession s where s.refreshTokenHash = :hash")
    Optional<UserSession> lockByHash(@Param("hash") String hash);
}
