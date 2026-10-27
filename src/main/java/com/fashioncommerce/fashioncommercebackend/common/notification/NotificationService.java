package com.fashioncommerce.fashioncommercebackend.common.notification;

import com.fashioncommerce.fashioncommercebackend.common.exception.ApiException;
import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationDtos.*;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {
    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'account.read_own', #userId)")
    public SliceResponse<NotificationResponse> list(UUID userId, boolean unreadOnly, int limit, int offset) {
        validatePage(limit, offset);
        List<NotificationResponse> rows = notifications.findForUser(userId, unreadOnly, limit + 1, offset);
        boolean hasMore = rows.size() > limit;
        return new SliceResponse<>(hasMore ? rows.subList(0, limit) : rows, limit, offset, hasMore);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'account.read_own', #userId)")
    public UnreadCountResponse unreadCount(UUID userId) {
        return new UnreadCountResponse(notifications.unreadCount(userId));
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'account.read_own', #userId)")
    public NotificationResponse markRead(UUID userId, UUID notificationId) {
        if (!notifications.markRead(notificationId, userId)) throw notFound();
        return notifications.findForUser(notificationId, userId).orElseThrow(NotificationService::notFound);
    }

    @Transactional
    @PreAuthorize("@platformAuthorization.hasOwnPermission(authentication, 'account.read_own', #userId)")
    public void markAllRead(UUID userId) {
        notifications.markAllRead(userId);
    }

    private static void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAGINATION_INVALID", "Limit must be 1-100 and offset must be non-negative");
        }
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found");
    }
}

