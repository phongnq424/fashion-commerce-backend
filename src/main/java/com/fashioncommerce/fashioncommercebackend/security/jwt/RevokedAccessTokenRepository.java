package com.fashioncommerce.fashioncommercebackend.security.jwt;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class RevokedAccessTokenRepository {
    private final JdbcTemplate jdbc;

    public RevokedAccessTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isRevoked(UUID tokenId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from revoked_access_tokens
                    where token_id = ? and user_id = ? and expires_at > now()
                )
                """, Boolean.class, tokenId, userId));
    }

    public void revoke(UUID tokenId, UUID userId, Instant expiresAt, Instant revokedAt) {
        jdbc.update("delete from revoked_access_tokens where expires_at <= ?", revokedAt);
        jdbc.update("""
                insert into revoked_access_tokens (token_id, user_id, expires_at, revoked_at)
                values (?, ?, ?, ?)
                on conflict (token_id) do nothing
                """, tokenId, userId, expiresAt, revokedAt);
    }
}
