package com.fashioncommerce.fashioncommercebackend.common.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() { }

    public record NotificationResponse(UUID id, String type, String title, String body,
                                       Map<String, Object> payload, Instant readAt, Instant createdAt) { }

    public record UnreadCountResponse(long unreadCount) { }
}

