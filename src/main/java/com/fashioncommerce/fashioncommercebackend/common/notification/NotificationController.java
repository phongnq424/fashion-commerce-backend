package com.fashioncommerce.fashioncommercebackend.common.notification;

import com.fashioncommerce.fashioncommercebackend.common.notification.NotificationDtos.*;
import com.fashioncommerce.fashioncommercebackend.common.response.SliceResponse;
import com.fashioncommerce.fashioncommercebackend.security.principal.CurrentIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public SliceResponse<NotificationResponse> list(Authentication identity,
                                                    @RequestParam(defaultValue = "false") boolean unreadOnly,
                                                    @RequestParam(defaultValue = "20") int limit,
                                                    @RequestParam(defaultValue = "0") int offset) {
        return service.list(CurrentIdentity.userId(identity).orElseThrow(), unreadOnly, limit, offset);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(Authentication identity) {
        return service.unreadCount(CurrentIdentity.userId(identity).orElseThrow());
    }

    @PatchMapping("/{notificationId}/read")
    public NotificationResponse markRead(Authentication identity, @PathVariable UUID notificationId) {
        return service.markRead(CurrentIdentity.userId(identity).orElseThrow(), notificationId);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(Authentication identity) {
        service.markAllRead(CurrentIdentity.userId(identity).orElseThrow());
    }
}
