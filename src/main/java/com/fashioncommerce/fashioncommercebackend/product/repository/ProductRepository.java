package com.fashioncommerce.fashioncommercebackend.product.repository;

import com.fashioncommerce.fashioncommercebackend.product.dto.ProductDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class ProductRepository {
    private static final String PRODUCT_COLUMNS = """
            p.id, p.shop_id, p.category_id, p.brand_id, p.seller_product_code, p.name, p.slug,
            p.description, p.material, p.fit_type, p.target_gender, p.care_instructions,
            p.size_chart, p.status, p.published_at, p.created_at, p.updated_at, s.owner_user_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ProductRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<CategoryResponse> categories() {
        return jdbc.query("""
                select id,parent_id,name,slug,description from categories where is_active
                order by sort_order,name
                """, (rs, row) -> new CategoryResponse(rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getString("description")));
    }

    public List<BrandResponse> brands() {
        return jdbc.query("""
                select id,name,slug,logo_key from brands where is_active order by name
                """, (rs, row) -> new BrandResponse(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("slug"), rs.getString("logo_key")));
    }

    public boolean activeCategory(UUID id) {
        return exists("select exists(select 1 from categories where id=? and is_active)", id);
    }

    public boolean activeBrand(UUID id) {
        return exists("select exists(select 1 from brands where id=? and is_active)", id);
    }

    public UUID create(UUID actorId, UUID shopId, CreateProductRequest request, String slug) {
        UUID productId = UUID.randomUUID();
        jdbc.update("""
                insert into products
                    (id,shop_id,category_id,brand_id,seller_product_code,name,slug,description,
                     material,fit_type,target_gender,care_instructions,size_chart,status)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT')
                """, productId, shopId, request.categoryId(), request.brandId(), nullable(request.sellerProductCode()),
                clean(request.name()), slug, clean(request.description()), nullable(request.material()),
                nullable(request.fitType()), request.targetGender().name(), nullable(request.careInstructions()),
                request.sizeChart() == null ? null : json(request.sizeChart()));

        Map<String, UUID> options = new LinkedHashMap<>();
        Map<String, UUID> values = new HashMap<>();
        for (OptionRequest option : request.options()) {
            UUID optionId = UUID.randomUUID();
            String type = option.type().name();
            options.put(type, optionId);
            jdbc.update("""
                    insert into product_options (id,product_id,option_type,display_name,sort_order)
                    values (?,?,?,?,?)
                    """, optionId, productId, type, clean(option.displayName()), option.sortOrder());
            for (OptionValueRequest value : option.values()) {
                UUID valueId = UUID.randomUUID();
                String code = normalize(value.code());
                values.put(type + "=" + code, valueId);
                jdbc.update("""
                        insert into product_option_values (id,option_id,product_id,code,label,hex_color,sort_order)
                        values (?,?,?,?,?,?,?)
                        """, valueId, optionId, productId, code, clean(value.label()), nullable(value.hexColor()), value.sortOrder());
            }
        }

        Map<String, UUID> variants = new HashMap<>();
        for (VariantRequest variant : request.variants()) {
            UUID variantId = UUID.randomUUID();
            String sku = normalize(variant.sellerSku());
            variants.put(sku, variantId);
            jdbc.update("""
                    insert into product_variants
                        (id,product_id,shop_id,seller_sku,barcode,option_signature,price_minor,
                         compare_at_price_minor,weight_grams,length_mm,width_mm,height_mm)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, variantId, productId, shopId, sku, nullable(variant.barcode()),
                    signature(variant.selections()), variant.priceMinor(), variant.compareAtPriceMinor(),
                    variant.weightGrams(), variant.lengthMm(), variant.widthMm(), variant.heightMm());
            for (Map.Entry<String, String> selection : variant.selections().entrySet()) {
                String type = normalize(selection.getKey());
                UUID optionId = options.get(type);
                UUID valueId = values.get(type + "=" + normalize(selection.getValue()));
                jdbc.update("""
                        insert into variant_option_values (variant_id,product_id,option_id,value_id)
                        values (?,?,?,?)
                        """, variantId, productId, optionId, valueId);
            }
            jdbc.update("""
                    insert into inventory_balances
                        (variant_id,shop_id,quantity_on_hand,quantity_reserved,reorder_point)
                    values (?,?,?,0,?)
                    """, variantId, shopId, variant.initialStock(), variant.reorderPoint());
            if (variant.initialStock() > 0) {
                jdbc.update("""
                        insert into inventory_movements
                            (id,variant_id,shop_id,quantity_delta,quantity_after,reason,reference_type,reference_id,performed_by,note)
                        values (?,?,?,?,?,'STOCK_IN','PRODUCT_CREATE',?,?,?)
                        """, UUID.randomUUID(), variantId, shopId, variant.initialStock(), variant.initialStock(),
                        productId, actorId, "Initial product stock");
            }
        }

        for (ImageRequest image : request.images()) {
            UUID variantId = image.variantSellerSku() == null ? null : variants.get(normalize(image.variantSellerSku()));
            jdbc.update("""
                    insert into product_images
                        (id,product_id,variant_id,storage_key,alt_text,sort_order,is_primary)
                    values (?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), productId, variantId, clean(image.storageKey()),
                    nullable(image.altText()), image.sortOrder(), image.primary());
        }
        return productId;
    }

    public Optional<ProductRow> row(UUID productId) {
        return queryRow("where p.id=?", productId);
    }

    public Optional<ProductRow> rowForUpdate(UUID productId) {
        return jdbc.query("select " + PRODUCT_COLUMNS + " from products p join shops s on s.id=p.shop_id " +
                "where p.id=? for update of p", this::mapRow, productId).stream().findFirst();
    }

    public ProductResponse response(UUID productId) {
        ProductRow row = row(productId).orElseThrow();
        return new ProductResponse(row.id(), row.shopId(), row.categoryId(), row.brandId(), row.sellerProductCode(),
                row.name(), row.slug(), row.description(), row.material(), row.fitType(), row.targetGender(),
                row.careInstructions(), row.sizeChart(), row.status(), row.publishedAt(), options(productId),
                variants(productId), images(productId), row.createdAt(), row.updatedAt());
    }

    public Optional<PublicProductResponse> publicProduct(UUID productId) {
        return row(productId).filter(row -> "ACTIVE".equals(row.status())).map(row -> publicResponse(response(productId)));
    }

    public List<ProductResponse> shopProducts(UUID shopId, int limitPlusOne, int offset) {
        return jdbc.queryForList("""
                select id from products where shop_id=? order by created_at desc limit ? offset ?
                """, UUID.class, shopId, limitPlusOne, offset).stream().map(this::response).toList();
    }

    public List<PublicProductResponse> activeProducts(UUID categoryId, int limitPlusOne, int offset) {
        List<UUID> ids = categoryId == null
                ? jdbc.queryForList("select id from products where status='ACTIVE' order by published_at desc limit ? offset ?",
                    UUID.class, limitPlusOne, offset)
                : jdbc.queryForList("select id from products where status='ACTIVE' and category_id=? order by published_at desc limit ? offset ?",
                    UUID.class, categoryId, limitPlusOne, offset);
        return ids.stream().map(this::response).map(this::publicResponse).toList();
    }

    public void update(UUID productId, UpdateProductRequest request) {
        jdbc.update("""
                update products set category_id=coalesce(?,category_id), brand_id=coalesce(?,brand_id),
                    seller_product_code=coalesce(?,seller_product_code), name=coalesce(?,name),
                    description=coalesce(?,description), material=coalesce(?,material),
                    fit_type=coalesce(?,fit_type), target_gender=coalesce(?,target_gender),
                    care_instructions=coalesce(?,care_instructions), size_chart=coalesce(?::jsonb,size_chart)
                where id=?
                """, request.categoryId(), request.brandId(), nullable(request.sellerProductCode()),
                nullable(request.name()), nullable(request.description()), nullable(request.material()),
                nullable(request.fitType()), request.targetGender() == null ? null : request.targetGender().name(),
                nullable(request.careInstructions()), request.sizeChart() == null ? null : json(request.sizeChart()), productId);
    }

    public boolean readyToPublish(UUID productId) {
        return exists("""
                select exists(
                    select 1 from products p
                    where p.id=? and p.status in ('DRAFT','ARCHIVED')
                      and exists(select 1 from product_variants v where v.product_id=p.id and v.status='ACTIVE')
                      and exists(select 1 from product_images i where i.product_id=p.id and i.is_primary)
                      and not exists(
                          select 1 from product_variants v where v.product_id=p.id and v.status='ACTIVE'
                            and not exists(select 1 from inventory_balances b where b.variant_id=v.id)))
                """, productId);
    }

    public boolean publish(UUID productId) {
        return jdbc.update("""
                update products set status='ACTIVE', published_at=coalesce(published_at,now()),
                    submitted_at=null, reviewed_by=null, reviewed_at=null, rejection_reason=null
                where id=? and status in ('DRAFT','ARCHIVED')
                """, productId) == 1;
    }

    public boolean unpublish(UUID productId) {
        return jdbc.update("update products set status='ARCHIVED' where id=? and status='ACTIVE'", productId) == 1;
    }

    public Optional<InventoryRow> inventoryForUpdate(UUID variantId, UUID shopId) {
        return jdbc.query("""
                select b.variant_id,b.quantity_on_hand,b.quantity_reserved,b.reorder_point,p.id product_id
                from inventory_balances b join product_variants v on v.id=b.variant_id
                join products p on p.id=v.product_id
                where b.variant_id=? and b.shop_id=? for update of b
                """, (rs, row) -> new InventoryRow(rs.getObject("variant_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getInt("quantity_on_hand"),
                rs.getInt("quantity_reserved"), rs.getInt("reorder_point")), variantId, shopId)
                .stream().findFirst();
    }

    public void setInventory(UUID actorId, UUID shopId, InventoryRow before, InventoryRequest request) {
        int delta = request.quantityOnHand() - before.onHand();
        jdbc.update("""
                update inventory_balances set quantity_on_hand=?,reorder_point=?,version=version+1 where variant_id=?
                """, request.quantityOnHand(), request.reorderPoint(), before.variantId());
        if (delta != 0) {
            jdbc.update("""
                    insert into inventory_movements
                        (id,variant_id,shop_id,quantity_delta,quantity_after,reason,reference_type,reference_id,performed_by,note)
                    values (?,?,?,?,?,'ADJUSTMENT','MANUAL_PRODUCT_STOCK',?,?,?)
                    """, UUID.randomUUID(), before.variantId(), shopId, delta, request.quantityOnHand(),
                    before.productId(), actorId, nullable(request.note()));
        }
    }

    public UUID createCase(UUID reporterId, UUID productId, ReportProductRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into product_moderation_cases
                    (id,product_id,reporter_user_id,reason_code,description,evidence_keys)
                values (?,?,?,?,?,?::jsonb)
                """, id, productId, reporterId, request.reason().name(), nullable(request.description()),
                json(request.evidenceKeys() == null ? List.of() : request.evidenceKeys()));
        return id;
    }

    public Optional<ModerationCaseResponse> moderationCase(UUID caseId) {
        return jdbc.query(caseSql() + " where c.id=?", this::mapCase, caseId).stream().findFirst();
    }

    public List<ModerationCaseResponse> moderationCases(String status, int limitPlusOne, int offset) {
        return jdbc.query(caseSql() + " where c.status=? order by c.created_at limit ? offset ?",
                this::mapCase, status, limitPlusOne, offset);
    }

    public Optional<ModerationCaseResponse> moderationCaseForUpdate(UUID caseId) {
        return jdbc.query(caseSql() + " where c.id=? for update of c", this::mapCase, caseId).stream().findFirst();
    }

    public boolean resolveCase(UUID caseId, UUID moderatorId, String action, String note) {
        String status = "DISMISS".equals(action) ? "DISMISSED" : "RESOLVED";
        return jdbc.update("""
                update product_moderation_cases set status=?,assigned_to=?,resolution_action=?,
                    resolution_note=?,resolved_at=now() where id=? and status in ('OPEN','UNDER_REVIEW')
                """, status, moderatorId, action, note, caseId) == 1;
    }

    public boolean moderateProduct(UUID productId, ModerationAction action) {
        return switch (action) {
            case WARN -> exists("select exists(select 1 from products where id=? and status in ('ACTIVE','HIDDEN'))", productId);
            case HIDE -> jdbc.update("update products set status='HIDDEN' where id=? and status='ACTIVE'", productId) == 1;
            case REMOVE -> jdbc.update("update products set status='REMOVED' where id=? and status in ('ACTIVE','HIDDEN')", productId) == 1;
            case RESTORE -> jdbc.update("update products set status='ACTIVE' where id=? and status in ('HIDDEN','REMOVED')", productId) == 1;
            case DISMISS -> exists("select exists(select 1 from products where id=? and status in ('ACTIVE','HIDDEN','REMOVED'))", productId);
        };
    }

    private List<OptionResponse> options(UUID productId) {
        return jdbc.query("""
                select id,option_type,display_name,sort_order from product_options
                where product_id=? order by sort_order,option_type
                """, (rs, row) -> {
            UUID optionId = rs.getObject("id", UUID.class);
            List<OptionValueResponse> values = jdbc.query("""
                    select id,code,label,hex_color,sort_order from product_option_values
                    where option_id=? order by sort_order,code
                    """, (valueRs, valueRow) -> new OptionValueResponse(valueRs.getObject("id", UUID.class),
                    valueRs.getString("code"), valueRs.getString("label"), valueRs.getString("hex_color"),
                    valueRs.getInt("sort_order")), optionId);
            return new OptionResponse(optionId, rs.getString("option_type"), rs.getString("display_name"),
                    rs.getInt("sort_order"), values);
        }, productId);
    }

    private List<VariantResponse> variants(UUID productId) {
        return jdbc.query("""
                select v.id,v.seller_sku,v.barcode,v.price_minor,v.compare_at_price_minor,v.weight_grams,
                       v.length_mm,v.width_mm,v.height_mm,v.status,b.quantity_on_hand,b.quantity_reserved,b.reorder_point
                from product_variants v join inventory_balances b on b.variant_id=v.id
                where v.product_id=? order by v.created_at
                """, (rs, row) -> {
            UUID variantId = rs.getObject("id", UUID.class);
            Map<String, String> selections = new LinkedHashMap<>();
            jdbc.query("""
                    select o.option_type,v.code from variant_option_values x
                    join product_options o on o.id=x.option_id
                    join product_option_values v on v.id=x.value_id
                    where x.variant_id=? order by o.option_type
                    """, (org.springframework.jdbc.core.RowCallbackHandler) valueRs ->
                    selections.put(valueRs.getString(1), valueRs.getString(2)), variantId);
            int onHand = rs.getInt("quantity_on_hand");
            int reserved = rs.getInt("quantity_reserved");
            return new VariantResponse(variantId, rs.getString("seller_sku"), rs.getString("barcode"), selections,
                    rs.getLong("price_minor"), nullableLong(rs, "compare_at_price_minor"), rs.getInt("weight_grams"),
                    nullableInt(rs, "length_mm"), nullableInt(rs, "width_mm"), nullableInt(rs, "height_mm"),
                    rs.getString("status"), onHand, reserved, onHand - reserved, rs.getInt("reorder_point"));
        }, productId);
    }

    private List<ImageResponse> images(UUID productId) {
        return jdbc.query("""
                select id,variant_id,storage_key,alt_text,sort_order,is_primary from product_images
                where product_id=? order by sort_order,created_at
                """, (rs, row) -> new ImageResponse(rs.getObject("id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getString("storage_key"), rs.getString("alt_text"),
                rs.getInt("sort_order"), rs.getBoolean("is_primary")), productId);
    }

    private PublicProductResponse publicResponse(ProductResponse product) {
        List<PublicVariantResponse> publicVariants = product.variants().stream()
                .filter(variant -> "ACTIVE".equals(variant.status()))
                .map(variant -> new PublicVariantResponse(variant.id(), variant.sellerSku(), variant.selections(),
                        variant.priceMinor(), variant.compareAtPriceMinor(), variant.availableQuantity()))
                .toList();
        return new PublicProductResponse(product.id(), product.shopId(), product.categoryId(), product.brandId(),
                product.name(), product.slug(), product.description(), product.material(), product.fitType(),
                product.targetGender(), product.careInstructions(), product.sizeChart(), product.publishedAt(),
                product.options(), publicVariants, product.images());
    }

    private Optional<ProductRow> queryRow(String clause, Object... args) {
        return jdbc.query("select " + PRODUCT_COLUMNS + " from products p join shops s on s.id=p.shop_id " + clause,
                this::mapRow, args).stream().findFirst();
    }

    private ProductRow mapRow(ResultSet rs, int row) throws SQLException {
        return new ProductRow(rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class),
                rs.getString("seller_product_code"), rs.getString("name"), rs.getString("slug"),
                rs.getString("description"), rs.getString("material"), rs.getString("fit_type"),
                rs.getString("target_gender"), rs.getString("care_instructions"),
                parseObject(rs.getString("size_chart")), rs.getString("status"), instant(rs, "published_at"),
                rs.getObject("owner_user_id", UUID.class), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private ModerationCaseResponse mapCase(ResultSet rs, int row) throws SQLException {
        return new ModerationCaseResponse(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("product_name"), rs.getObject("shop_id", UUID.class),
                rs.getObject("reporter_user_id", UUID.class), rs.getString("reason_code"),
                rs.getString("description"), parseList(rs.getString("evidence_keys")), rs.getString("status"),
                rs.getObject("assigned_to", UUID.class), rs.getString("resolution_action"),
                rs.getString("resolution_note"), instant(rs, "resolved_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static String caseSql() {
        return """
                select c.id,c.product_id,p.name product_name,p.shop_id,c.reporter_user_id,c.reason_code,
                       c.description,c.evidence_keys,c.status,c.assigned_to,c.resolution_action,
                       c.resolution_note,c.resolved_at,c.created_at,c.updated_at
                from product_moderation_cases c join products p on p.id=c.product_id
                """;
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalArgumentException("Value is not serializable", ex); }
    }

    private Map<String, Object> parseObject(String value) {
        if (value == null) return null;
        try { return mapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException ex) { throw new IllegalStateException("Invalid product JSON", ex); }
    }

    private List<String> parseList(String value) {
        try { return mapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException ex) { throw new IllegalStateException("Invalid evidence JSON", ex); }
    }

    public static String signature(Map<String, String> selections) {
        return selections.entrySet().stream()
                .map(entry -> normalize(entry.getKey()) + "=" + normalize(entry.getValue()))
                .sorted().reduce((a, b) -> a + "|" + b).orElse("DEFAULT");
    }

    private static String normalize(String value) { return value.strip().toUpperCase(Locale.ROOT); }
    private static String clean(String value) { return value.strip(); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column); return rs.wasNull() ? null : value;
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column); return rs.wasNull() ? null : value;
    }

    public record ProductRow(UUID id, UUID shopId, UUID categoryId, UUID brandId, String sellerProductCode,
                             String name, String slug, String description, String material, String fitType,
                             String targetGender, String careInstructions, Map<String, Object> sizeChart,
                             String status, Instant publishedAt, UUID ownerUserId,
                             Instant createdAt, Instant updatedAt) { }
    public record InventoryRow(UUID variantId, UUID productId, int onHand, int reserved, int reorderPoint) { }
}
