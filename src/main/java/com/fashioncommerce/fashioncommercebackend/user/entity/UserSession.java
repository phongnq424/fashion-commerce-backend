package com.fashioncommerce.fashioncommercebackend.user.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_sessions")
public class UserSession {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "refresh_token_hash", nullable = false, length = 64, columnDefinition = "char(64)") private String refreshTokenHash;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "revoked_at") private Instant revokedAt;
    @Column(name = "last_used_at") private Instant lastUsedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected UserSession() { }
    public UserSession(UUID id, UUID userId, String hash, Instant now, Instant expiresAt) {
        this.id = id; this.userId = userId; refreshTokenHash = hash; createdAt = now; this.expiresAt = expiresAt;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getRefreshTokenHash() { return refreshTokenHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public boolean isValid(Instant now) { return revokedAt == null && expiresAt.isAfter(now); }
    public void rotate(String hash, Instant now) { refreshTokenHash = hash; lastUsedAt = now; }
    public void revoke(Instant now) { revokedAt = now; }
}
