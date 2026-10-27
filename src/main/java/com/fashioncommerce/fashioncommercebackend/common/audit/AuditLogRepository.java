package com.fashioncommerce.fashioncommercebackend.common.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
public class AuditLogRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditLogRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(UUID actorUserId, UUID shopId, String action, String entityType,
                       UUID entityId, Map<String, ?> changes) {
        jdbc.update("""
                insert into audit_logs
                    (id, actor_user_id, shop_id, action, entity_type, entity_id, changes)
                values (?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), actorUserId, shopId, action, entityType, entityId, json(changes));
    }

    private String json(Map<String, ?> changes) {
        try {
            return objectMapper.writeValueAsString(changes == null ? Map.of() : changes);
        }
        catch (JacksonException ex) {
            throw new IllegalArgumentException("Audit changes are not serializable", ex);
        }
    }
}
