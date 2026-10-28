package com.fashioncommerce.fashioncommercebackend.common.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class ModerationActionRepository {
    private final JdbcTemplate jdbc;

    public ModerationActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void sellerDecision(UUID moderatorId, UUID applicationId, String action, String reason,
                               String previousStatus, String newStatus) {
        jdbc.update("""
                insert into moderation_actions
                    (id, moderator_user_id, target_application_id, action, reason, previous_status, new_status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), moderatorId, applicationId, action, reason, previousStatus, newStatus);
    }

    public void shopDecision(UUID moderatorId, UUID shopId, String action, String reason,
                             String previousStatus, String newStatus) {
        jdbc.update("""
                insert into moderation_actions
                    (id, moderator_user_id, target_shop_id, action, reason, previous_status, new_status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), moderatorId, shopId, action, reason, previousStatus, newStatus);
    }

    public void productDecision(UUID moderatorId, UUID productId, String action, String reason,
                                String previousStatus, String newStatus) {
        jdbc.update("""
                insert into moderation_actions
                    (id, moderator_user_id, target_product_id, action, reason, previous_status, new_status)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), moderatorId, productId, action, reason, previousStatus, newStatus);
    }
}

