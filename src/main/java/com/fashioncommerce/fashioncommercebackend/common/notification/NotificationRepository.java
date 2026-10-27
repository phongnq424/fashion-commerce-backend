package com.fashioncommerce.fashioncommercebackend.common.notification;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationDtos.NotificationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void create(UUID userId, String type, String title, String body, Map<String, ?> payload) {
        jdbc.update("""
                insert into notifications (id, user_id, notification_type, title, body, payload)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), userId, type, title, body, json(payload));
    }

    public List<NotificationResponse> findForUser(UUID userId, boolean unreadOnly, int limitPlusOne, int offset) {
        String unread = unreadOnly ? " and read_at is null" : "";
        return jdbc.query("select id, notification_type, title, body, payload, read_at, created_at " +
                        "from notifications where user_id = ?" + unread +
                        " order by created_at desc limit ? offset ?",
                this::map, userId, limitPlusOne, offset);
    }

    public Optional<NotificationResponse> findForUser(UUID id, UUID userId) {
        return jdbc.query("""
                select id, notification_type, title, body, payload, read_at, created_at
                from notifications where id = ? and user_id = ?
                """, this::map, id, userId).stream().findFirst();
    }

    public boolean markRead(UUID id, UUID userId) {
        return jdbc.update("""
                update notifications set read_at = coalesce(read_at, now()) where id = ? and user_id = ?
                """, id, userId) == 1;
    }

    public int markAllRead(UUID userId) {
        return jdbc.update("update notifications set read_at = now() where user_id = ? and read_at is null", userId);
    }

    public long unreadCount(UUID userId) {
        Long count = jdbc.queryForObject("select count(*) from notifications where user_id = ? and read_at is null",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        }
        catch (JacksonException ex) {
            throw new IllegalArgumentException("Notification payload is not serializable", ex);
        }
    }

    private NotificationResponse map(ResultSet rs, int row) throws SQLException {
        return new NotificationResponse(rs.getObject("id", UUID.class), rs.getString("notification_type"),
                rs.getString("title"), rs.getString("body"), parseJson(rs.getString("payload")),
                instant(rs, "read_at"), instant(rs, "created_at"));
    }

    private Map<String, Object> parseJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        }
        catch (JacksonException ex) {
            throw new IllegalStateException("Invalid notification payload JSON in database", ex);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}


