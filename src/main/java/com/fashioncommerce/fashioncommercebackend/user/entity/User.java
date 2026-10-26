package com.fashioncommerce.fashioncommercebackend.user.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id private UUID id;
    @Column(nullable = false, length = 320) private String email;
    @Column(length = 24) private String phone;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "email_verified_at") private Instant emailVerifiedAt;
    @Column(name = "last_login_at") private Instant lastLoginAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected User() { }
    public User(UUID id, String email, String phone, String displayName, Instant now) {
        this.id = id; this.email = email; this.phone = phone; this.displayName = displayName;
        this.status = "ACTIVE"; this.createdAt = now; this.updatedAt = now;
    }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public boolean isActive() { return "ACTIVE".equals(status); }
    public void loggedIn(Instant now) { lastLoginAt = now; updatedAt = now; }
}
