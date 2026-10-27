package com.fashioncommerce.fashioncommercebackend.seller.repository;

import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerDtos.ApplicationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SellerRepository {
    private static final String COLUMNS = """
            id, applicant_user_id, proposed_shop_name, contact_email, contact_phone,
            legal_name, legal_identifier_last4, document_key, status, reviewed_by,
            reviewed_at, review_note, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public SellerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApplicationResponse create(UUID userId, UUID id, String proposedShopName, String contactEmail,
                                      String contactPhone, String legalName, String legalIdentifierLast4,
                                      String documentKey) {
        jdbc.update("""
                insert into seller_applications
                    (id, applicant_user_id, proposed_shop_name, contact_email, contact_phone,
                     legal_name, legal_identifier_last4, document_key)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, userId, proposedShopName, contactEmail, contactPhone, legalName,
                legalIdentifierLast4, documentKey);
        return find(id).orElseThrow();
    }

    public Optional<ApplicationResponse> find(UUID id) {
        return jdbc.query("select " + COLUMNS + " from seller_applications where id = ?", this::map, id)
                .stream().findFirst();
    }

    public Optional<ApplicationResponse> findForUpdate(UUID id) {
        return jdbc.query("select " + COLUMNS + " from seller_applications where id = ? for update", this::map, id)
                .stream().findFirst();
    }

    public List<ApplicationResponse> findByApplicant(UUID userId, int limitPlusOne, int offset) {
        return jdbc.query("select " + COLUMNS + " from seller_applications where applicant_user_id = ? " +
                        "order by created_at desc limit ? offset ?", this::map, userId, limitPlusOne, offset);
    }

    public List<ApplicationResponse> findAll(String status, int limitPlusOne, int offset) {
        if (status == null) {
            return jdbc.query("select " + COLUMNS + " from seller_applications order by created_at desc limit ? offset ?",
                    this::map, limitPlusOne, offset);
        }
        return jdbc.query("select " + COLUMNS + " from seller_applications where status = ? " +
                        "order by created_at desc limit ? offset ?", this::map, status, limitPlusOne, offset);
    }

    public boolean markUnderReview(UUID id, UUID reviewerId) {
        return jdbc.update("""
                update seller_applications
                set status = 'UNDER_REVIEW', reviewed_by = ?, reviewed_at = now(), review_note = null
                where id = ? and status = 'PENDING'
                """, reviewerId, id) == 1;
    }

    public boolean decide(UUID id, UUID reviewerId, String decision, String note) {
        return jdbc.update("""
                update seller_applications
                set status = ?, reviewed_by = ?, reviewed_at = now(), review_note = ?
                where id = ? and status in ('PENDING', 'UNDER_REVIEW')
                """, decision, reviewerId, note, id) == 1;
    }

    public boolean cancel(UUID id, UUID applicantId) {
        return jdbc.update("""
                update seller_applications
                set status = 'CANCELLED'
                where id = ? and applicant_user_id = ? and status = 'PENDING'
                """, id, applicantId) == 1;
    }

    public boolean updatePending(UUID id, UUID applicantId, String proposedShopName, String contactEmail,
                                 String contactPhone, String legalName, String legalIdentifierLast4,
                                 String documentKey) {
        return jdbc.update("""
                update seller_applications set proposed_shop_name = ?, contact_email = ?, contact_phone = ?,
                    legal_name = ?, legal_identifier_last4 = ?, document_key = ?
                where id = ? and applicant_user_id = ? and status = 'PENDING'
                """, proposedShopName, contactEmail, contactPhone, legalName, legalIdentifierLast4,
                documentKey, id, applicantId) == 1;
    }

    public boolean isApprovedApplicant(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from seller_applications
                              where applicant_user_id = ? and status = 'APPROVED')
                """, Boolean.class, userId));
    }

    public Optional<ApplicationResponse> approvedApplicationForUserForUpdate(UUID userId) {
        return jdbc.query("select " + COLUMNS + " from seller_applications " +
                        "where applicant_user_id = ? and status = 'APPROVED' for update",
                this::map, userId).stream().findFirst();
    }

    public Optional<ApplicationResponse> approvedApplicationForUpdate(UUID applicationId, UUID applicantId) {
        return jdbc.query("select " + COLUMNS + " from seller_applications " +
                        "where id = ? and applicant_user_id = ? and status = 'APPROVED' for update",
                this::map, applicationId, applicantId).stream().findFirst();
    }

    private ApplicationResponse map(ResultSet rs, int row) throws SQLException {
        return new ApplicationResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("applicant_user_id", UUID.class),
                rs.getString("proposed_shop_name"),
                rs.getString("contact_email"),
                rs.getString("contact_phone"),
                rs.getString("legal_name"),
                rs.getString("legal_identifier_last4"),
                rs.getString("document_key"),
                rs.getString("status"),
                rs.getObject("reviewed_by", UUID.class),
                instant(rs, "reviewed_at"),
                rs.getString("review_note"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
