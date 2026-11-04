package com.fashioncommerce.fashioncommercebackend.product.repository;

import com.fashioncommerce.fashioncommercebackend.common.asset.AssetUrlResolver;
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
            p.size_chart, p.version, p.status, p.published_at, p.created_at, p.updated_at, s.owner_user_id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AssetUrlResolver assetUrls;

    public ProductRepository(JdbcTemplate jdbc, ObjectMapper mapper, AssetUrlResolver assetUrls) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.assetUrls = assetUrls;
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
                """, (rs, row) -> {
            String logoKey = rs.getString("logo_key");
            return new BrandResponse(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getString("slug"), logoKey, assetUrls.publicUrl(logoKey));
        });
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
        List<Object[]> optionRows = new ArrayList<>();
        List<Object[]> valueRows = new ArrayList<>();
        for (OptionRequest option : request.options()) {
            UUID optionId = UUID.randomUUID();
            String type = option.type().name();
            options.put(type, optionId);
            optionRows.add(new Object[]{optionId, productId, type, clean(option.displayName()), option.sortOrder()});
            for (OptionValueRequest value : option.values()) {
                UUID valueId = UUID.randomUUID();
                String code = normalize(value.code());
                values.put(type + "=" + code, valueId);
                valueRows.add(new Object[]{valueId, optionId, productId, code, clean(value.label()),
                        nullable(value.hexColor()), value.sortOrder()});
            }
        }
        jdbc.batchUpdate("""
                insert into product_options (id,product_id,option_type,display_name,sort_order)
                values (?,?,?,?,?)
                """, optionRows);
        jdbc.batchUpdate("""
                insert into product_option_values (id,option_id,product_id,code,label,hex_color,sort_order)
                values (?,?,?,?,?,?,?)
                """, valueRows);

        Map<String, UUID> variants = new HashMap<>();
        List<Object[]> variantRows = new ArrayList<>();
        List<Object[]> selectionRows = new ArrayList<>();
        List<Object[]> inventoryRows = new ArrayList<>();
        List<Object[]> movementRows = new ArrayList<>();
        for (VariantRequest variant : request.variants()) {
            UUID variantId = UUID.randomUUID();
            String sku = normalize(variant.sellerSku());
            variants.put(sku, variantId);
            variantRows.add(new Object[]{variantId, productId, shopId, sku, nullable(variant.barcode()),
                    signature(variant.selections()), variant.priceMinor(), variant.compareAtPriceMinor(),
                    variant.weightGrams(), variant.lengthMm(), variant.widthMm(), variant.heightMm()});
            for (Map.Entry<String, String> selection : variant.selections().entrySet()) {
                String type = normalize(selection.getKey());
                UUID optionId = options.get(type);
                UUID valueId = values.get(type + "=" + normalize(selection.getValue()));
                selectionRows.add(new Object[]{variantId, productId, optionId, valueId});
            }
            inventoryRows.add(new Object[]{variantId, shopId, variant.initialStock(), variant.reorderPoint()});
            if (variant.initialStock() > 0) {
                movementRows.add(new Object[]{UUID.randomUUID(), variantId, shopId, variant.initialStock(),
                        variant.initialStock(), productId, actorId, "Initial product stock"});
            }
        }
        jdbc.batchUpdate("""
                insert into product_variants
                    (id,product_id,shop_id,seller_sku,barcode,option_signature,price_minor,
                     compare_at_price_minor,weight_grams,length_mm,width_mm,height_mm)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, variantRows);
        jdbc.batchUpdate("""
                insert into variant_option_values (variant_id,product_id,option_id,value_id)
                values (?,?,?,?)
                """, selectionRows);
        jdbc.batchUpdate("""
                insert into inventory_balances
                    (variant_id,shop_id,quantity_on_hand,quantity_reserved,reorder_point)
                values (?,?,?,0,?)
                """, inventoryRows);
        if (!movementRows.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into inventory_movements
                        (id,variant_id,shop_id,quantity_delta,quantity_after,reason,reference_type,reference_id,performed_by,note)
                    values (?,?,?,?,?,'STOCK_IN','PRODUCT_CREATE',?,?,?)
                    """, movementRows);
        }

        List<Object[]> imageRows = new ArrayList<>();
        for (ImageRequest image : request.images()) {
            UUID variantId = image.variantSellerSku() == null ? null : variants.get(normalize(image.variantSellerSku()));
            imageRows.add(new Object[]{UUID.randomUUID(), productId, variantId, clean(image.storageKey()),
                    nullable(image.altText()), image.sortOrder(), image.primary()});
        }
        jdbc.batchUpdate("""
                insert into product_images
                    (id,product_id,variant_id,storage_key,alt_text,sort_order,is_primary)
                values (?,?,?,?,?,?,?)
                """, imageRows);
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
        return response(row(productId).orElseThrow());
    }

    public ProductResponse response(ProductRow row) {
        UUID productId = row.id();
        return new ProductResponse(row.id(), row.shopId(), row.categoryId(), row.brandId(), row.sellerProductCode(),
                row.name(), row.slug(), row.description(), row.material(), row.fitType(), row.targetGender(),
                row.careInstructions(), row.sizeChart(), row.version(), row.status(), row.publishedAt(), options(productId),
                variants(productId), images(productId), row.createdAt(), row.updatedAt());
    }

    public Optional<PublicProductResponse> publicProduct(UUID productId) {
        return queryRow("""
                where p.id=? and p.status='ACTIVE' and s.status='ACTIVE'
                  and exists(select 1 from product_variants v join inventory_balances b on b.variant_id=v.id
                             where v.product_id=p.id and v.status='ACTIVE')
                """, productId)
                .map(row -> publicResponse(response(row)));
    }

    public List<ShopProductSummaryResponse> shopProducts(UUID shopId, int limitPlusOne, int offset) {
        return jdbc.query("""
                select p.id,p.shop_id,p.seller_product_code,p.name,p.slug,p.status,p.version,
                       image.storage_key primary_image_key,stats.min_price,stats.max_price,
                       stats.variant_count,stats.active_variant_count,stats.quantity_on_hand,
                       stats.quantity_reserved,p.published_at,p.updated_at
                from products p
                left join lateral (
                    select min(v.price_minor) min_price,max(v.price_minor) max_price,
                           count(*) variant_count,count(*) filter (where v.status='ACTIVE') active_variant_count,
                           coalesce(sum(b.quantity_on_hand),0) quantity_on_hand,
                           coalesce(sum(b.quantity_reserved),0) quantity_reserved
                    from product_variants v join inventory_balances b on b.variant_id=v.id
                    where v.product_id=p.id
                ) stats on true
                left join lateral (
                    select i.storage_key from product_images i where i.product_id=p.id
                    order by (i.variant_id is null) desc,i.is_primary desc,i.sort_order,i.created_at limit 1
                ) image on true
                where p.shop_id=? order by p.created_at desc limit ? offset ?
                """, (rs, row) -> new ShopProductSummaryResponse(
                rs.getObject("id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getString("seller_product_code"), rs.getString("name"), rs.getString("slug"),
                rs.getString("status"), rs.getLong("version"), rs.getString("primary_image_key"),
                assetUrls.publicUrl(rs.getString("primary_image_key")),
                nullableLong(rs, "min_price"), nullableLong(rs, "max_price"),
                rs.getInt("variant_count"), rs.getInt("active_variant_count"),
                rs.getLong("quantity_on_hand"), rs.getLong("quantity_reserved"),
                instant(rs, "published_at"), instant(rs, "updated_at")), shopId, limitPlusOne, offset);
    }

    public List<CatalogEntry> activeProducts(CatalogFilter filter, CatalogCursor cursor, int limitPlusOne) {
        StringBuilder where = new StringBuilder("""
                where p.status='ACTIVE' and s.status='ACTIVE' and v.status='ACTIVE'
                """);
        List<Object> args = new ArrayList<>();
        if (filter.query() != null) {
            where.append(" and to_tsvector('simple',coalesce(p.name,'') || ' ' || coalesce(p.description,'')) @@ websearch_to_tsquery('simple',?)");
            args.add(filter.query());
        }
        if (filter.categoryId() != null) { where.append(" and p.category_id=?"); args.add(filter.categoryId()); }
        if (filter.brandId() != null) { where.append(" and p.brand_id=?"); args.add(filter.brandId()); }
        if (filter.gender() != null) { where.append(" and p.target_gender=?"); args.add(filter.gender().name()); }
        if (filter.minPriceMinor() != null) { where.append(" and v.price_minor>=?"); args.add(filter.minPriceMinor()); }
        if (filter.maxPriceMinor() != null) { where.append(" and v.price_minor<=?"); args.add(filter.maxPriceMinor()); }
        if (filter.inStock()) where.append(" and b.quantity_on_hand>b.quantity_reserved");

        StringBuilder sql = new StringBuilder("""
                with catalog as (
                    select p.id,coalesce(p.published_at,p.created_at) published_at,
                           lower(p.name) sort_name,min(v.price_minor) min_price
                    from products p join shops s on s.id=p.shop_id
                    join product_variants v on v.product_id=p.id
                    join inventory_balances b on b.variant_id=v.id
                """).append(where).append(" group by p.id,p.published_at,p.created_at,p.name), page as (select * from catalog");
        if (cursor != null) appendCursor(sql, args, filter.sort(), cursor);
        sql.append(switch (filter.sort()) {
            case NEWEST -> " order by published_at desc,id";
            case PRICE_ASC -> " order by min_price,id";
            case PRICE_DESC -> " order by min_price desc,id";
            case NAME_ASC -> " order by sort_name,id";
        }).append(" limit ?) select c.id,c.published_at,c.sort_name,c.min_price,p.shop_id,s.name shop_name,")
                .append("s.slug shop_slug,p.category_id,p.brand_id,p.name,p.slug,p.target_gender,")
                .append("image.storage_key primary_image_key,stats.summary_min_price,stats.max_price,")
                .append("stats.compare_at_price,stats.variant_count,stats.available_quantity ")
                .append("from page c join products p on p.id=c.id join shops s on s.id=p.shop_id ")
                .append("join lateral (select min(v.price_minor) summary_min_price,max(v.price_minor) max_price,")
                .append("min(v.compare_at_price_minor) compare_at_price,count(*) variant_count,")
                .append("coalesce(sum(greatest(b.quantity_on_hand-b.quantity_reserved,0)),0) available_quantity ")
                .append("from product_variants v join inventory_balances b on b.variant_id=v.id ")
                .append("where v.product_id=p.id and v.status='ACTIVE') stats on true ")
                .append("left join lateral (select i.storage_key from product_images i ")
                .append("where i.product_id=p.id and (i.variant_id is null or exists(")
                .append("select 1 from product_variants av where av.id=i.variant_id and av.status='ACTIVE')) ")
                .append("order by (i.variant_id is null) desc,i.is_primary desc,i.sort_order,i.created_at limit 1) image on true ")
                .append(switch (filter.sort()) {
                    case NEWEST -> "order by c.published_at desc,c.id";
                    case PRICE_ASC -> "order by c.min_price,c.id";
                    case PRICE_DESC -> "order by c.min_price desc,c.id";
                    case NAME_ASC -> "order by c.sort_name,c.id";
                });
        args.add(limitPlusOne);
        return jdbc.query(sql.toString(), (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            PublicProductSummaryResponse summary = new PublicProductSummaryResponse(id,
                    rs.getObject("shop_id", UUID.class), rs.getString("shop_name"), rs.getString("shop_slug"),
                    rs.getObject("category_id", UUID.class), rs.getObject("brand_id", UUID.class),
                    rs.getString("name"), rs.getString("slug"), rs.getString("target_gender"),
                    rs.getString("primary_image_key"), assetUrls.publicUrl(rs.getString("primary_image_key")),
                    rs.getLong("summary_min_price"),
                    rs.getLong("max_price"), nullableLong(rs, "compare_at_price"),
                    rs.getInt("variant_count"), rs.getLong("available_quantity"),
                    instant(rs, "published_at"));
            return new CatalogEntry(summary, summary.publishedAt(), rs.getLong("min_price"),
                    rs.getString("sort_name"), id);
        }, args.toArray());
    }

    private void appendCursor(StringBuilder sql, List<Object> args, ProductSort sort, CatalogCursor cursor) {
        switch (sort) {
            case NEWEST -> {
                sql.append(" where published_at<? or (published_at=? and id>?)");
                args.add(cursor.publishedAt()); args.add(cursor.publishedAt()); args.add(cursor.id());
            }
            case PRICE_ASC -> {
                sql.append(" where min_price>? or (min_price=? and id>?)");
                args.add(cursor.price()); args.add(cursor.price()); args.add(cursor.id());
            }
            case PRICE_DESC -> {
                sql.append(" where min_price<? or (min_price=? and id>?)");
                args.add(cursor.price()); args.add(cursor.price()); args.add(cursor.id());
            }
            case NAME_ASC -> {
                sql.append(" where sort_name>? or (sort_name=? and id>?)");
                args.add(cursor.name()); args.add(cursor.name()); args.add(cursor.id());
            }
        }
    }

    public boolean update(UUID productId, UpdateProductRequest request) {
        return jdbc.update("""
                update products set category_id=coalesce(?,category_id), brand_id=coalesce(?,brand_id),
                    seller_product_code=coalesce(?,seller_product_code), name=coalesce(?,name),
                    description=coalesce(?,description), material=coalesce(?,material),
                    fit_type=coalesce(?,fit_type), target_gender=coalesce(?,target_gender),
                    care_instructions=coalesce(?,care_instructions), size_chart=coalesce(?::jsonb,size_chart),
                    version=version+1
                where id=? and version=?
                """, request.categoryId(), request.brandId(), nullable(request.sellerProductCode()),
                nullable(request.name()), nullable(request.description()), nullable(request.material()),
                nullable(request.fitType()), request.targetGender() == null ? null : request.targetGender().name(),
                nullable(request.careInstructions()), request.sizeChart() == null ? null : json(request.sizeChart()),
                productId, request.expectedVersion()) == 1;
    }

    public boolean bumpVersion(UUID productId, long expectedVersion) {
        return jdbc.update("update products set version=version+1 where id=? and version=?", productId, expectedVersion) == 1;
    }

    public boolean readyToPublish(UUID productId) {
        return exists("""
                select exists(
                    select 1 from products p
                    where p.id=? and p.status in ('DRAFT','ARCHIVED')
                      and exists(select 1 from product_variants v where v.product_id=p.id and v.status='ACTIVE')
                      and exists(select 1 from product_images i where i.product_id=p.id
                                 and i.variant_id is null and i.is_primary)
                      and not exists(
                          select 1 from product_variants v where v.product_id=p.id and v.status='ACTIVE'
                            and not exists(select 1 from inventory_balances b where b.variant_id=v.id)))
                """, productId);
    }

    public boolean publish(UUID productId, long expectedVersion) {
        return jdbc.update("""
                update products set status='ACTIVE', published_at=coalesce(published_at,now()),
                    submitted_at=null, reviewed_by=null, reviewed_at=null, rejection_reason=null,
                    version=version+1
                where id=? and status in ('DRAFT','ARCHIVED') and version=?
                """, productId, expectedVersion) == 1;
    }

    public boolean unpublish(UUID productId, long expectedVersion) {
        return jdbc.update("update products set status='ARCHIVED',version=version+1 where id=? and status='ACTIVE' and version=?",
                productId, expectedVersion) == 1;
    }

    public Map<String, OptionSelection> optionSelections(UUID productId) {
        Map<String, OptionSelection> values = new LinkedHashMap<>();
        jdbc.query("""
                select o.option_type,o.id option_id,v.code,v.id value_id
                from product_options o join product_option_values v on v.option_id=o.id
                where o.product_id=?
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(
                normalize(rs.getString("option_type")) + "=" + normalize(rs.getString("code")),
                new OptionSelection(rs.getObject("option_id", UUID.class),
                        rs.getObject("value_id", UUID.class))), productId);
        return values;
    }

    public UUID createVariant(UUID actorId, UUID shopId, UUID productId, CreateVariantRequest request,
                              Map<String, OptionSelection> selections) {
        UUID variantId = UUID.randomUUID();
        jdbc.update("""
                insert into product_variants
                    (id,product_id,shop_id,seller_sku,barcode,option_signature,price_minor,
                     compare_at_price_minor,weight_grams,length_mm,width_mm,height_mm)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, variantId, productId, shopId, normalize(request.sellerSku()), nullable(request.barcode()),
                signature(request.selections()), request.priceMinor(), request.compareAtPriceMinor(),
                request.weightGrams(), request.lengthMm(), request.widthMm(), request.heightMm());
        List<Object[]> selectionRows = selections.values().stream()
                .map(selection -> new Object[]{variantId, productId, selection.optionId(), selection.valueId()})
                .toList();
        jdbc.batchUpdate("""
                insert into variant_option_values (variant_id,product_id,option_id,value_id)
                values (?,?,?,?)
                """, selectionRows);
        jdbc.update("""
                insert into inventory_balances
                    (variant_id,shop_id,quantity_on_hand,quantity_reserved,reorder_point)
                values (?,?,?,0,?)
                """, variantId, shopId, request.initialStock(), request.reorderPoint());
        if (request.initialStock() > 0) {
            jdbc.update("""
                    insert into inventory_movements
                        (id,variant_id,shop_id,quantity_delta,quantity_after,reason,reference_type,reference_id,performed_by,note)
                    values (?,?,?,?,?,'STOCK_IN','VARIANT_CREATE',?,?,?)
                    """, UUID.randomUUID(), variantId, shopId, request.initialStock(), request.initialStock(),
                    productId, actorId, "Initial variant stock");
        }
        return variantId;
    }

    public Optional<VariantRow> variantForUpdate(UUID productId, UUID variantId) {
        return jdbc.query("""
                select v.id,v.product_id,v.shop_id,v.seller_sku,v.status,v.price_minor,
                       v.compare_at_price_minor,b.quantity_reserved
                from product_variants v join inventory_balances b on b.variant_id=v.id
                where v.id=? and v.product_id=? for update of v
                """, (rs, row) -> new VariantRow(rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getObject("shop_id", UUID.class),
                rs.getString("seller_sku"), rs.getString("status"), rs.getLong("price_minor"),
                nullableLong(rs, "compare_at_price_minor"), rs.getInt("quantity_reserved")),
                variantId, productId).stream().findFirst();
    }

    public void updateVariant(UUID variantId, UpdateVariantRequest request) {
        jdbc.update("""
                update product_variants set
                    seller_sku=coalesce(?,seller_sku),
                    barcode=case when ? then ? else barcode end,
                    weight_grams=coalesce(?,weight_grams),length_mm=coalesce(?,length_mm),
                    width_mm=coalesce(?,width_mm),height_mm=coalesce(?,height_mm)
                where id=?
                """, request.sellerSku() == null ? null : normalize(request.sellerSku()),
                request.barcode() != null, nullable(request.barcode()), request.weightGrams(),
                request.lengthMm(), request.widthMm(), request.heightMm(), variantId);
    }

    public void updateVariantPrice(UUID variantId, UpdateVariantPriceRequest request) {
        jdbc.update("update product_variants set price_minor=?,compare_at_price_minor=? where id=?",
                request.priceMinor(), request.compareAtPriceMinor(), variantId);
    }

    public void updateVariantStatus(UUID variantId, VariantStatus status) {
        jdbc.update("update product_variants set status=? where id=?", status.name(), variantId);
    }

    public int activeVariantCount(UUID productId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from product_variants where product_id=? and status='ACTIVE'",
                Integer.class, productId));
    }

    public boolean variantBelongsToProduct(UUID variantId, UUID productId) {
        return exists("select exists(select 1 from product_variants where id=? and product_id=?)", variantId, productId);
    }

    public int imageCount(UUID productId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from product_images where product_id=?", Integer.class, productId));
    }

    public Optional<ImageRow> imageForUpdate(UUID productId, UUID imageId) {
        return jdbc.query("""
                select id,product_id,variant_id,is_primary from product_images
                where id=? and product_id=? for update
                """, (rs, row) -> new ImageRow(rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getObject("variant_id", UUID.class),
                rs.getBoolean("is_primary")), imageId, productId).stream().findFirst();
    }

    public UUID addImage(UUID productId, AddImageRequest request) {
        UUID id = UUID.randomUUID();
        if (request.primary()) clearPrimary(productId, request.variantId());
        jdbc.update("""
                insert into product_images (id,product_id,variant_id,storage_key,alt_text,sort_order,is_primary)
                values (?,?,?,?,?,?,?)
                """, id, productId, request.variantId(), clean(request.storageKey()), nullable(request.altText()),
                request.sortOrder(), request.primary());
        return id;
    }

    public void deleteImage(UUID imageId) {
        jdbc.update("delete from product_images where id=?", imageId);
    }

    public void reorderImages(List<ImageOrderItem> items) {
        jdbc.batchUpdate("update product_images set sort_order=? where id=?",
                items.stream().map(item -> new Object[]{item.sortOrder(), item.imageId()}).toList());
    }

    public void setPrimaryImage(UUID productId, ImageRow image) {
        clearPrimary(productId, image.variantId());
        jdbc.update("update product_images set is_primary=true where id=?", image.id());
    }

    private void clearPrimary(UUID productId, UUID variantId) {
        if (variantId == null) {
            jdbc.update("update product_images set is_primary=false where product_id=? and variant_id is null and is_primary", productId);
        } else {
            jdbc.update("update product_images set is_primary=false where variant_id=? and is_primary", variantId);
        }
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

    public List<ModerationCaseSummaryResponse> moderationCases(String status, int limitPlusOne, int offset) {
        return jdbc.query("""
                select c.id,c.product_id,p.name product_name,p.shop_id,c.reporter_user_id,
                       c.reason_code,c.status,c.assigned_to,c.created_at,c.updated_at
                from product_moderation_cases c join products p on p.id=c.product_id
                where c.status=? order by c.created_at limit ? offset ?
                """, (rs, row) -> new ModerationCaseSummaryResponse(
                rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getString("product_name"), rs.getObject("shop_id", UUID.class),
                rs.getObject("reporter_user_id", UUID.class), rs.getString("reason_code"),
                rs.getString("status"), rs.getObject("assigned_to", UUID.class),
                instant(rs, "created_at"), instant(rs, "updated_at")), status, limitPlusOne, offset);
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
            case HIDE -> jdbc.update("update products set status='HIDDEN',version=version+1 where id=? and status='ACTIVE'", productId) == 1;
            case REMOVE -> jdbc.update("update products set status='REMOVED',version=version+1 where id=? and status in ('ACTIVE','HIDDEN')", productId) == 1;
            case RESTORE -> jdbc.update("update products set status='ACTIVE',version=version+1 where id=? and status in ('HIDDEN','REMOVED')", productId) == 1;
            case DISMISS -> exists("select exists(select 1 from products where id=? and status in ('ACTIVE','HIDDEN','REMOVED'))", productId);
        };
    }

    private List<OptionResponse> options(UUID productId) {
        List<OptionJoinRow> rows = jdbc.query("""
                select o.id option_id,o.option_type,o.display_name,o.sort_order option_sort_order,
                       v.id value_id,v.code value_code,v.label value_label,v.hex_color,
                       v.sort_order value_sort_order
                from product_options o
                left join product_option_values v on v.option_id=o.id
                where o.product_id=?
                order by o.sort_order,o.option_type,v.sort_order,v.code
                """, (rs, row) -> new OptionJoinRow(
                rs.getObject("option_id", UUID.class), rs.getString("option_type"),
                rs.getString("display_name"), rs.getInt("option_sort_order"),
                rs.getObject("value_id", UUID.class), rs.getString("value_code"),
                rs.getString("value_label"), rs.getString("hex_color"),
                nullableInt(rs, "value_sort_order")), productId);

        Map<UUID, OptionHeader> headers = new LinkedHashMap<>();
        Map<UUID, List<OptionValueResponse>> values = new LinkedHashMap<>();
        for (OptionJoinRow row : rows) {
            headers.putIfAbsent(row.optionId(), new OptionHeader(row.optionId(), row.type(),
                    row.displayName(), row.sortOrder()));
            values.computeIfAbsent(row.optionId(), ignored -> new ArrayList<>());
            if (row.valueId() != null) {
                values.get(row.optionId()).add(new OptionValueResponse(row.valueId(), row.valueCode(),
                        row.valueLabel(), row.hexColor(), row.valueSortOrder()));
            }
        }
        return headers.values().stream().map(header -> new OptionResponse(header.id(), header.type(),
                header.displayName(), header.sortOrder(), List.copyOf(values.get(header.id())))).toList();
    }

    private List<VariantResponse> variants(UUID productId) {
        List<VariantJoinRow> rows = jdbc.query("""
                select v.id,v.seller_sku,v.barcode,v.price_minor,v.compare_at_price_minor,v.weight_grams,
                       v.length_mm,v.width_mm,v.height_mm,v.status,b.quantity_on_hand,b.quantity_reserved,b.reorder_point,
                       o.option_type selection_type,ov.code selection_code
                from product_variants v join inventory_balances b on b.variant_id=v.id
                left join variant_option_values x on x.variant_id=v.id
                left join product_options o on o.id=x.option_id
                left join product_option_values ov on ov.id=x.value_id
                where v.product_id=? order by v.created_at,o.option_type
                """, (rs, row) -> new VariantJoinRow(
                rs.getObject("id", UUID.class), rs.getString("seller_sku"), rs.getString("barcode"),
                rs.getLong("price_minor"), nullableLong(rs, "compare_at_price_minor"),
                rs.getInt("weight_grams"), nullableInt(rs, "length_mm"), nullableInt(rs, "width_mm"),
                nullableInt(rs, "height_mm"), rs.getString("status"), rs.getInt("quantity_on_hand"),
                rs.getInt("quantity_reserved"), rs.getInt("reorder_point"),
                rs.getString("selection_type"), rs.getString("selection_code")), productId);

        Map<UUID, VariantHeader> headers = new LinkedHashMap<>();
        Map<UUID, Map<String, String>> selections = new LinkedHashMap<>();
        for (VariantJoinRow row : rows) {
            headers.putIfAbsent(row.id(), new VariantHeader(row.id(), row.sellerSku(), row.barcode(),
                    row.priceMinor(), row.compareAtPriceMinor(), row.weightGrams(), row.lengthMm(),
                    row.widthMm(), row.heightMm(), row.status(), row.quantityOnHand(),
                    row.quantityReserved(), row.reorderPoint()));
            selections.computeIfAbsent(row.id(), ignored -> new LinkedHashMap<>());
            if (row.selectionType() != null) selections.get(row.id()).put(row.selectionType(), row.selectionCode());
        }
        return headers.values().stream().map(header -> {
            int available = header.quantityOnHand() - header.quantityReserved();
            return new VariantResponse(header.id(), header.sellerSku(), header.barcode(),
                    Collections.unmodifiableMap(selections.get(header.id())), header.priceMinor(),
                    header.compareAtPriceMinor(), header.weightGrams(), header.lengthMm(), header.widthMm(),
                    header.heightMm(), header.status(), header.quantityOnHand(), header.quantityReserved(),
                    available, header.reorderPoint());
        }).toList();
    }

    private List<ImageResponse> images(UUID productId) {
        return jdbc.query("""
                select id,variant_id,storage_key,alt_text,sort_order,is_primary from product_images
                where product_id=? order by sort_order,created_at
                """, (rs, row) -> {
            String storageKey = rs.getString("storage_key");
            return new ImageResponse(rs.getObject("id", UUID.class),
                    rs.getObject("variant_id", UUID.class), storageKey, assetUrls.publicUrl(storageKey),
                    rs.getString("alt_text"), rs.getInt("sort_order"), rs.getBoolean("is_primary"));
        }, productId);
    }

    private PublicProductResponse publicResponse(ProductResponse product) {
        List<PublicVariantResponse> publicVariants = product.variants().stream()
                .filter(variant -> "ACTIVE".equals(variant.status()))
                .map(variant -> new PublicVariantResponse(variant.id(), variant.sellerSku(), variant.selections(),
                        variant.priceMinor(), variant.compareAtPriceMinor(), variant.availableQuantity()))
                .toList();
        Set<UUID> activeVariantIds = publicVariants.stream().map(PublicVariantResponse::id).collect(java.util.stream.Collectors.toSet());
        List<ImageResponse> publicImages = product.images().stream()
                .filter(image -> image.variantId() == null || activeVariantIds.contains(image.variantId())).toList();
        return new PublicProductResponse(product.id(), product.shopId(), product.categoryId(), product.brandId(),
                product.name(), product.slug(), product.description(), product.material(), product.fitType(),
                product.targetGender(), product.careInstructions(), product.sizeChart(), product.publishedAt(),
                product.options(), publicVariants, publicImages);
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
                parseObject(rs.getString("size_chart")), rs.getLong("version"), rs.getString("status"), instant(rs, "published_at"),
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

    private record OptionJoinRow(UUID optionId, String type, String displayName, int sortOrder,
                                 UUID valueId, String valueCode, String valueLabel,
                                 String hexColor, Integer valueSortOrder) { }
    private record OptionHeader(UUID id, String type, String displayName, int sortOrder) { }
    private record VariantJoinRow(UUID id, String sellerSku, String barcode, long priceMinor,
                                  Long compareAtPriceMinor, int weightGrams, Integer lengthMm,
                                  Integer widthMm, Integer heightMm, String status,
                                  int quantityOnHand, int quantityReserved, int reorderPoint,
                                  String selectionType, String selectionCode) { }
    private record VariantHeader(UUID id, String sellerSku, String barcode, long priceMinor,
                                 Long compareAtPriceMinor, int weightGrams, Integer lengthMm,
                                 Integer widthMm, Integer heightMm, String status,
                                 int quantityOnHand, int quantityReserved, int reorderPoint) { }

    public record ProductRow(UUID id, UUID shopId, UUID categoryId, UUID brandId, String sellerProductCode,
                             String name, String slug, String description, String material, String fitType,
                             String targetGender, String careInstructions, Map<String, Object> sizeChart,
                             long version, String status, Instant publishedAt, UUID ownerUserId,
                             Instant createdAt, Instant updatedAt) { }
    public record InventoryRow(UUID variantId, UUID productId, int onHand, int reserved, int reorderPoint) { }
    public record OptionSelection(UUID optionId, UUID valueId) { }
    public record VariantRow(UUID id, UUID productId, UUID shopId, String sellerSku, String status,
                             long priceMinor, Long compareAtPriceMinor, int reserved) { }
    public record ImageRow(UUID id, UUID productId, UUID variantId, boolean primary) { }
    public record CatalogFilter(String query, UUID categoryId, UUID brandId, Gender gender,
                                Long minPriceMinor, Long maxPriceMinor, boolean inStock, ProductSort sort) { }
    public record CatalogCursor(Instant publishedAt, Long price, String name, UUID id) { }
    public record CatalogEntry(PublicProductSummaryResponse product, Instant publishedAt, long price, String name, UUID id) { }
}
