package com.fashioncommerce.fashioncommercebackend.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_credentials")
public class UserCredential {
    @Id @Column(name = "user_id") private UUID userId;
    @Column(name = "password_hash", nullable = false, length = 255) private String passwordHash;
    @Column(name = "password_changed_at", nullable = false) private Instant passwordChangedAt;
    @Column(name = "failed_attempts", nullable = false) private int failedAttempts;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected UserCredential() { }
    public UserCredential(UUID userId, String passwordHash, Instant now) {
        this.userId = userId; this.passwordHash = passwordHash; passwordChangedAt = now; createdAt = now; updatedAt = now;
    }
    public String getPasswordHash() { return passwordHash; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public boolean isLocked(Instant now) { return lockedUntil != null && lockedUntil.isAfter(now); }
    public void failed(Instant now) {
        if (lockedUntil != null && !lockedUntil.isAfter(now)) { failedAttempts = 0; lockedUntil = null; }
        failedAttempts++;
        if (failedAttempts >= 5) lockedUntil = now.plusSeconds(15 * 60);
        updatedAt = now;
    }
    public void succeeded(Instant now) { failedAttempts = 0; lockedUntil = null; updatedAt = now; }
}
