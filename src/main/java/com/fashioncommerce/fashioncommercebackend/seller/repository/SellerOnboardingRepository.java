package com.fashioncommerce.fashioncommercebackend.seller.repository;

import com.fashioncommerce.fashioncommercebackend.seller.dto.SellerOnboardingDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Repository
public class SellerOnboardingRepository {
    private static final String ROW_COLUMNS = """
            o.id, o.applicant_user_id, o.shop_id, o.status, o.submitted_at,
            o.reviewed_by, o.reviewed_at, o.review_note, o.completed_at, o.created_at, o.updated_at,
            s.name, s.slug, s.contact_email, s.contact_phone, s.logo_key, s.description, s.banner_key, s.status as shop_status
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SellerOnboardingRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public OnboardingRow create(UUID userId, ShopProfileRequest request, String slug) {
        UUID shopId = UUID.randomUUID();
        UUID onboardingId = UUID.randomUUID();
        jdbc.update("""
                insert into shops
                    (id, owner_user_id, application_id, name, slug, description, logo_key, banner_key,
                     contact_email, contact_phone, pickup_address, status, activated_at)
                values (?, ?, null, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, 'DRAFT', null)
                """, shopId, userId, clean(request.name()), slug, nullable(request.description()),
                nullable(request.avatarKey()), nullable(request.coverKey()), lower(request.email()), clean(request.phone()));
        jdbc.update("insert into shop_settings (shop_id) values (?)", shopId);
        UUID memberId = UUID.randomUUID();
        jdbc.update("""
                insert into shop_members (id, shop_id, user_id, invited_by, status, joined_at)
                values (?, ?, ?, ?, 'ACTIVE', now())
                """, memberId, shopId, userId, userId);
        jdbc.update("""
                insert into shop_member_roles (member_id, role_id, scope, granted_by)
                select ?, id, 'SHOP', ? from roles where code = 'SHOP_OWNER' and scope = 'SHOP'
                """, memberId, userId);
        jdbc.update("""
                insert into seller_onboardings (id, applicant_user_id, shop_id)
                values (?, ?, ?)
                """, onboardingId, userId, shopId);
        return find(onboardingId).orElseThrow();
    }

    public Optional<OnboardingRow> find(UUID id) {
        return queryRow("where o.id = ?", id);
    }

    public Optional<OnboardingRow> findForUpdate(UUID id) {
        return jdbc.query("select " + ROW_COLUMNS + " from seller_onboardings o join shops s on s.id=o.shop_id " +
                        "where o.id = ? for update of o, s", this::mapRow, id).stream().findFirst();
    }

    public Optional<OnboardingRow> activeForUser(UUID userId) {
        return queryRow("where o.applicant_user_id = ? and o.status not in ('COMPLETED','CANCELLED')", userId);
    }

    public List<UUID> idsByTaxStatus(String status, int limitPlusOne, int offset) {
        return jdbc.queryForList("""
                select o.id from seller_onboardings o join seller_tax_profiles t on t.onboarding_id=o.id
                where t.status = ? order by t.updated_at limit ? offset ?
                """, UUID.class, status, limitPlusOne, offset);
    }

    public List<UUID> idsByIdentityStatus(String status, int limitPlusOne, int offset) {
        return jdbc.queryForList("""
                select o.id from seller_onboardings o join seller_identity_verifications i on i.onboarding_id=o.id
                where i.status = ? order by i.updated_at limit ? offset ?
                """, UUID.class, status, limitPlusOne, offset);
    }

    public List<UUID> idsByReviewStatus(String status, int limitPlusOne, int offset) {
        return jdbc.queryForList("""
                select id from seller_onboardings where status=?
                order by coalesce(submitted_at, created_at) limit ? offset ?
                """, UUID.class, status, limitPlusOne, offset);
    }

    public void updateShop(UUID shopId, ShopProfileRequest request) {
        jdbc.update("""
                update shops set name=?, description=?, logo_key=?, banner_key=?, contact_email=?, contact_phone=?
                where id=? and status='DRAFT'
                """, clean(request.name()), nullable(request.description()), nullable(request.avatarKey()),
                nullable(request.coverKey()), lower(request.email()), clean(request.phone()), shopId);
    }

    public PickupAddressResponse upsertPickupAddress(UUID onboardingId, UUID shopId, PickupAddressRequest request) {
        if (request.defaultPickup()) {
            jdbc.update("update shop_pickup_addresses set is_default_pickup=false where shop_id=? and is_default_pickup", shopId);
        }
        Optional<UUID> existing = jdbc.query("""
                select id from shop_pickup_addresses where shop_id=? and is_active order by created_at limit 1
                for update
                """, (rs, row) -> rs.getObject(1, UUID.class), shopId).stream().findFirst();
        UUID addressId = existing.orElseGet(UUID::randomUUID);
        if (existing.isPresent()) {
            jdbc.update("""
                    update shop_pickup_addresses set contact_name=?, contact_phone=?, province_code=?, province_name=?,
                        ward_code=?, ward_name=?, address_detail=?, is_default_pickup=? where id=?
                    """, clean(request.contactName()), clean(request.contactPhone()), nullable(request.provinceCode()),
                    clean(request.provinceName()), nullable(request.wardCode()), clean(request.wardName()),
                    clean(request.addressDetail()), request.defaultPickup(), addressId);
        } else {
            jdbc.update("""
                    insert into shop_pickup_addresses
                        (id, shop_id, contact_name, contact_phone, province_code, province_name,
                         ward_code, ward_name, address_detail, is_default_pickup)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, addressId, shopId, clean(request.contactName()), clean(request.contactPhone()),
                    nullable(request.provinceCode()), clean(request.provinceName()), nullable(request.wardCode()),
                    clean(request.wardName()), clean(request.addressDetail()), request.defaultPickup());
        }
        if (request.defaultPickup()) {
            jdbc.update("update shops set pickup_address=?::jsonb where id=?", json(addressMap(request)), shopId);
        }
        jdbc.update("update seller_onboardings set pickup_completed_at=now() where id=?", onboardingId);
        return pickupAddress(addressId).orElseThrow();
    }

    public List<PickupAddressResponse> pickupAddresses(UUID shopId) {
        return jdbc.query("""
                select id, contact_name, contact_phone, province_code, province_name, ward_code,
                       ward_name, address_detail, is_default_pickup, is_active
                from shop_pickup_addresses where shop_id=? and is_active order by is_default_pickup desc, created_at
                """, this::mapPickup, shopId);
    }

    public boolean activeAddressBelongsToShop(UUID addressId, UUID shopId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from shop_pickup_addresses where id=? and shop_id=? and is_active)
                """, Boolean.class, addressId, shopId));
    }

    public List<ShippingProviderResponse> shippingProviders(UUID shopId) {
        return jdbc.query("""
                select p.code, p.name, p.description, p.is_active, coalesce(c.is_enabled,false) enabled,
                       c.pickup_address_id
                from shipping_providers p left join shop_shipping_configs c
                  on c.provider_id=p.id and c.shop_id=?
                order by p.name
                """, (rs, row) -> new ShippingProviderResponse(rs.getString("code"), rs.getString("name"),
                rs.getString("description"), rs.getBoolean("is_active"), rs.getBoolean("enabled"),
                rs.getObject("pickup_address_id", UUID.class)), shopId);
    }

    public Map<String, UUID> activeProviderIds(Collection<String> codes) {
        if (codes.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
        Map<String, UUID> result = new HashMap<>();
        jdbc.query("select code,id from shipping_providers where is_active and code in (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString(1), rs.getObject(2, UUID.class)),
                codes.toArray());
        return result;
    }

    public void configureShipping(UUID onboardingId, UUID shopId, UUID addressId,
                                  Collection<ShippingSelection> selections, Map<String, UUID> providerIds) {
        jdbc.update("delete from shop_shipping_configs where shop_id=?", shopId);
        for (ShippingSelection selection : selections) {
            jdbc.update("""
                    insert into shop_shipping_configs (shop_id, provider_id, pickup_address_id, is_enabled)
                    values (?, ?, ?, ?)
                    """, shopId, providerIds.get(normalizeCode(selection.providerCode())), addressId, selection.enabled());
        }
        jdbc.update("update seller_onboardings set shipping_completed_at=now() where id=?", onboardingId);
    }

    public void saveTax(UUID onboardingId, TaxProfileRequest request) {
        jdbc.update("""
                insert into seller_tax_profiles
                    (onboarding_id, business_type, legal_name, business_name, legal_representative_name,
                     tax_code, business_registration_number, issued_date, issued_by, registered_address,
                     invoice_email, registration_document_key, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, 'DRAFT')
                on conflict (onboarding_id) do update set
                    business_type=excluded.business_type, legal_name=excluded.legal_name,
                    business_name=excluded.business_name, legal_representative_name=excluded.legal_representative_name,
                    tax_code=excluded.tax_code, business_registration_number=excluded.business_registration_number,
                    issued_date=excluded.issued_date, issued_by=excluded.issued_by,
                    registered_address=excluded.registered_address, invoice_email=excluded.invoice_email,
                    registration_document_key=excluded.registration_document_key, status='DRAFT',
                    reviewed_by=null, reviewed_at=null, review_note=null
                """, onboardingId, request.businessType().name(), nullable(request.legalName()),
                nullable(request.businessName()), nullable(request.legalRepresentativeName()), clean(request.taxCode()),
                nullable(request.businessRegistrationNumber()), request.issuedDate(), nullable(request.issuedBy()),
                json(addressMap(request.registeredAddress())), lower(request.invoiceEmail()),
                nullable(request.registrationDocumentKey()));
    }

    public Optional<TaxProfileResponse> taxProfile(UUID onboardingId) {
        return jdbc.query("select * from seller_tax_profiles where onboarding_id=?", this::mapTax, onboardingId)
                .stream().findFirst();
    }

    public boolean submitTax(UUID onboardingId) {
        int tax = jdbc.update("update seller_tax_profiles set status='PENDING' where onboarding_id=? and status='DRAFT'",
                onboardingId);
        if (tax != 1) return false;
        return jdbc.update("""
                update seller_onboardings set status='TAX_PENDING'
                where id=? and status in ('DRAFT','TAX_REJECTED')
                  and pickup_completed_at is not null and shipping_completed_at is not null
                  and exists(select 1 from shop_shipping_configs where shop_id=seller_onboardings.shop_id and is_enabled)
                """, onboardingId) == 1;
    }

    public boolean decideTax(UUID onboardingId, UUID reviewerId, ReviewDecision decision, String note) {
        return jdbc.update("""
                update seller_tax_profiles set status=?, reviewed_by=?, reviewed_at=now(), review_note=?
                where onboarding_id=? and status='PENDING'
                """, decision.name(), reviewerId, note, onboardingId) == 1;
    }

    public void setOnboardingStatus(UUID onboardingId, String status) {
        jdbc.update("update seller_onboardings set status=? where id=?", status, onboardingId);
    }

    public void saveIdentity(UUID onboardingId, IdentityVerificationRequest request) {
        jdbc.update("""
                insert into seller_identity_verifications
                    (onboarding_id, legal_name, document_number_last4, front_document_key,
                     back_document_key, selfie_document_key, status)
                values (?, ?, ?, ?, ?, ?, 'DRAFT')
                on conflict (onboarding_id) do update set legal_name=excluded.legal_name,
                    document_number_last4=excluded.document_number_last4,
                    front_document_key=excluded.front_document_key,
                    back_document_key=excluded.back_document_key,
                    selfie_document_key=excluded.selfie_document_key, status='DRAFT',
                    reviewed_by=null, reviewed_at=null, review_note=null
                """, onboardingId, clean(request.legalName()), request.documentNumberLast4(),
                clean(request.frontDocumentKey()), clean(request.backDocumentKey()), clean(request.selfieDocumentKey()));
    }

    public Optional<IdentityVerificationResponse> identity(UUID onboardingId) {
        return jdbc.query("select * from seller_identity_verifications where onboarding_id=?", this::mapIdentity,
                onboardingId).stream().findFirst();
    }

    public boolean decideIdentity(UUID onboardingId, UUID reviewerId, ReviewDecision decision, String note) {
        return jdbc.update("""
                update seller_identity_verifications set status=?, reviewed_by=?, reviewed_at=now(), review_note=?
                where onboarding_id=? and status='PENDING'
                """, decision.name(), reviewerId, note, onboardingId) == 1;
    }

    public boolean acceptPolicy(UUID onboardingId, String version) {
        return jdbc.update("""
                insert into seller_policy_acceptances (onboarding_id, policy_version)
                select ?, version from seller_policy_versions
                where version=? and is_active and effective_at <= now()
                on conflict (onboarding_id, policy_version) do nothing
                """, onboardingId, version) == 1;
    }

    public List<PolicyVersionResponse> policies(UUID onboardingId) {
        return jdbc.query("""
                select p.version, p.title, p.is_required, p.effective_at,
                       (a.onboarding_id is not null) accepted
                from seller_policy_versions p
                left join seller_policy_acceptances a
                  on a.policy_version=p.version and a.onboarding_id=?
                where p.is_active and p.effective_at <= now()
                order by p.effective_at, p.version
                """, (rs, row) -> new PolicyVersionResponse(rs.getString("version"), rs.getString("title"),
                rs.getBoolean("is_required"), instant(rs, "effective_at"), rs.getBoolean("accepted")), onboardingId);
    }

    public boolean allRequiredPoliciesAccepted(UUID onboardingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select not exists (
                    select 1 from seller_policy_versions p
                    where p.is_active and p.is_required and p.effective_at <= now()
                      and not exists (
                          select 1 from seller_policy_acceptances a
                          where a.onboarding_id=? and a.policy_version=p.version))
                """, Boolean.class, onboardingId));
    }

    public List<String> acceptedPolicies(UUID onboardingId) {
        return jdbc.queryForList("""
                select policy_version from seller_policy_acceptances where onboarding_id=? order by accepted_at
                """, String.class, onboardingId);
    }

    public boolean submitForReview(UUID onboardingId) {
        int onboarding = jdbc.update("""
                update seller_onboardings o
                set status='PENDING_REVIEW', submitted_at=now(), reviewed_by=null, reviewed_at=null, review_note=null
                where o.id=? and o.status in ('DRAFT','REJECTED')
                  and o.pickup_completed_at is not null and o.shipping_completed_at is not null
                  and exists(select 1 from shop_shipping_configs c where c.shop_id=o.shop_id and c.is_enabled)
                  and exists(select 1 from seller_tax_profiles t where t.onboarding_id=o.id and t.status in ('DRAFT','REJECTED'))
                  and exists(select 1 from seller_identity_verifications i where i.onboarding_id=o.id and i.status in ('DRAFT','REJECTED'))
                  and not exists (
                      select 1 from seller_policy_versions p
                      where p.is_active and p.is_required and p.effective_at <= now()
                        and not exists (
                            select 1 from seller_policy_acceptances a
                            where a.onboarding_id=o.id and a.policy_version=p.version))
                """, onboardingId);
        if (onboarding != 1) return false;
        jdbc.update("update seller_tax_profiles set status='PENDING' where onboarding_id=?", onboardingId);
        jdbc.update("update seller_identity_verifications set status='PENDING' where onboarding_id=?", onboardingId);
        return true;
    }

    public void rejectReview(UUID onboardingId, UUID reviewerId, String note) {
        jdbc.update("""
                update seller_tax_profiles set status='REJECTED', reviewed_by=?, reviewed_at=now(), review_note=?
                where onboarding_id=? and status='PENDING'
                """, reviewerId, note, onboardingId);
        jdbc.update("""
                update seller_identity_verifications set status='REJECTED', reviewed_by=?, reviewed_at=now(), review_note=?
                where onboarding_id=? and status='PENDING'
                """, reviewerId, note, onboardingId);
        jdbc.update("""
                update seller_onboardings set status='REJECTED', reviewed_by=?, reviewed_at=now(), review_note=?
                where id=? and status='PENDING_REVIEW'
                """, reviewerId, note, onboardingId);
    }

    public UUID approveAndComplete(UUID onboardingId, UUID shopId, UUID reviewerId, String note) {
        jdbc.update("""
                update seller_tax_profiles set status='APPROVED', reviewed_by=?, reviewed_at=now(), review_note=?
                where onboarding_id=? and status='PENDING'
                """, reviewerId, note, onboardingId);
        jdbc.update("""
                update seller_identity_verifications set status='APPROVED', reviewed_by=?, reviewed_at=now(), review_note=?
                where onboarding_id=? and status='PENDING'
                """, reviewerId, note, onboardingId);
        UUID applicationId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into seller_applications
                    (id, applicant_user_id, proposed_shop_name, contact_email, contact_phone,
                     legal_name, legal_identifier_last4, document_key, status, reviewed_by,
                     reviewed_at, review_note)
                select ?, o.applicant_user_id, s.name, s.contact_email, s.contact_phone,
                       coalesce(t.legal_name, t.business_name), i.document_number_last4,
                       i.front_document_key, 'APPROVED', i.reviewed_by, i.reviewed_at,
                       'Completed multi-step seller onboarding'
                from seller_onboardings o
                join shops s on s.id=o.shop_id
                join seller_tax_profiles t on t.onboarding_id=o.id and t.status='APPROVED'
                join seller_identity_verifications i on i.onboarding_id=o.id and i.status='APPROVED'
                where o.id=? and o.shop_id=? and o.status='PENDING_REVIEW'
                  and not exists (
                      select 1 from seller_policy_versions p
                      where p.is_active and p.is_required and p.effective_at <= now()
                        and not exists (
                            select 1 from seller_policy_acceptances a
                            where a.onboarding_id=o.id and a.policy_version=p.version))
                """, applicationId, onboardingId, shopId);
        if (inserted != 1) throw new IllegalStateException("Onboarding completion preconditions changed");
        jdbc.update("""
                update shops set application_id=?, status='ACTIVE', activated_at=now() where id=? and status='DRAFT'
                """, applicationId, shopId);
        jdbc.update("""
                update seller_onboardings set status='COMPLETED', reviewed_by=?, reviewed_at=now(),
                    review_note=?, completed_at=now() where id=? and status='PENDING_REVIEW'
                """, reviewerId, note, onboardingId);
        return applicationId;
    }

    public OnboardingResponse response(OnboardingRow row) {
        return new OnboardingResponse(row.id(), row.applicantUserId(), row.status(), row.shop(),
                pickupAddresses(row.shopId()), shippingProviders(row.shopId()),
                taxProfile(row.id()).orElse(null), identity(row.id()).orElse(null),
                acceptedPolicies(row.id()), row.submittedAt(), row.reviewedBy(), row.reviewedAt(),
                row.reviewNote(), row.completedAt(), row.createdAt(), row.updatedAt());
    }

    private Optional<OnboardingRow> queryRow(String clause, Object... args) {
        return jdbc.query("select " + ROW_COLUMNS + " from seller_onboardings o join shops s on s.id=o.shop_id " + clause,
                this::mapRow, args).stream().findFirst();
    }

    private Optional<PickupAddressResponse> pickupAddress(UUID id) {
        return jdbc.query("""
                select id, contact_name, contact_phone, province_code, province_name, ward_code,
                       ward_name, address_detail, is_default_pickup, is_active
                from shop_pickup_addresses where id=?
                """, this::mapPickup, id).stream().findFirst();
    }

    private OnboardingRow mapRow(ResultSet rs, int row) throws SQLException {
        UUID shopId = rs.getObject("shop_id", UUID.class);
        ShopProfileResponse shop = new ShopProfileResponse(shopId, rs.getString("name"), rs.getString("slug"),
                rs.getString("contact_email"), rs.getString("contact_phone"), rs.getString("logo_key"),
                rs.getString("description"), rs.getString("banner_key"), rs.getString("shop_status"));
        return new OnboardingRow(rs.getObject("id", UUID.class), rs.getObject("applicant_user_id", UUID.class),
                shopId, rs.getString("status"), shop, instant(rs, "submitted_at"),
                rs.getObject("reviewed_by", UUID.class), instant(rs, "reviewed_at"), rs.getString("review_note"),
                instant(rs, "completed_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private PickupAddressResponse mapPickup(ResultSet rs, int row) throws SQLException {
        return new PickupAddressResponse(rs.getObject("id", UUID.class), rs.getString("contact_name"),
                rs.getString("contact_phone"), rs.getString("province_code"), rs.getString("province_name"),
                rs.getString("ward_code"), rs.getString("ward_name"), rs.getString("address_detail"),
                rs.getBoolean("is_default_pickup"), rs.getBoolean("is_active"));
    }

    private TaxProfileResponse mapTax(ResultSet rs, int row) throws SQLException {
        LocalDate date = rs.getObject("issued_date", LocalDate.class);
        return new TaxProfileResponse(BusinessType.valueOf(rs.getString("business_type")), rs.getString("legal_name"),
                rs.getString("business_name"), rs.getString("legal_representative_name"), rs.getString("tax_code"),
                rs.getString("business_registration_number"), date, rs.getString("issued_by"),
                parseJson(rs.getString("registered_address")), rs.getString("invoice_email"),
                rs.getString("registration_document_key"), rs.getString("status"),
                rs.getObject("reviewed_by", UUID.class), instant(rs, "reviewed_at"), rs.getString("review_note"));
    }

    private IdentityVerificationResponse mapIdentity(ResultSet rs, int row) throws SQLException {
        return new IdentityVerificationResponse(rs.getString("document_type"), rs.getString("legal_name"),
                rs.getString("document_number_last4"), rs.getString("front_document_key"),
                rs.getString("back_document_key"), rs.getString("selfie_document_key"), rs.getString("status"),
                rs.getObject("reviewed_by", UUID.class), instant(rs, "reviewed_at"), rs.getString("review_note"));
    }

    private Map<String, Object> addressMap(PickupAddressRequest value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("contactName", clean(value.contactName()));
        map.put("contactPhone", clean(value.contactPhone()));
        map.put("provinceCode", nullable(value.provinceCode()));
        map.put("provinceName", clean(value.provinceName()));
        map.put("wardCode", nullable(value.wardCode()));
        map.put("wardName", clean(value.wardName()));
        map.put("addressDetail", clean(value.addressDetail()));
        return map;
    }

    private Map<String, Object> addressMap(RegisteredAddress value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provinceCode", nullable(value.provinceCode()));
        map.put("provinceName", clean(value.provinceName()));
        map.put("wardCode", nullable(value.wardCode()));
        map.put("wardName", clean(value.wardName()));
        map.put("addressDetail", clean(value.addressDetail()));
        return map;
    }

    private String json(Map<String, ?> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalArgumentException("Address is not serializable", ex); }
    }

    private Map<String, Object> parseJson(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException ex) { throw new IllegalStateException("Invalid address JSON", ex); }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalizeCode(String value) { return value.strip().toUpperCase(Locale.ROOT); }
    private static String clean(String value) { return value.strip(); }
    private static String lower(String value) { return value.strip().toLowerCase(Locale.ROOT); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    public record OnboardingRow(UUID id, UUID applicantUserId, UUID shopId, String status,
                                ShopProfileResponse shop, Instant submittedAt, UUID reviewedBy,
                                Instant reviewedAt, String reviewNote, Instant completedAt,
                                Instant createdAt, Instant updatedAt) { }
}
