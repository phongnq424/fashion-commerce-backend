package com.fashioncommerce.fashioncommercebackend.commerce.repository;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
public class CommerceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CommerceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public CartRow ensureCart(UUID buyerId) {
        jdbc.update("insert into carts (user_id) values (?) on conflict (user_id) do nothing", buyerId);
        return cart(buyerId).orElseThrow();
    }

    public Optional<CartRow> cart(UUID buyerId) {
        return jdbc.query("select id,user_id,version,created_at,updated_at from carts where user_id=?",
                this::mapCart, buyerId).stream().findFirst();
    }

    public Optional<CartRow> cartForUpdate(UUID buyerId) {
        return jdbc.query("select id,user_id,version,created_at,updated_at from carts where user_id=? for update",
                this::mapCart, buyerId).stream().findFirst();
    }

    public List<CartItemRow> cartItems(UUID cartId) {
        return jdbc.query("""
                select ci.id,ci.cart_id,ci.variant_id,ci.quantity,ci.selected,
                       v.product_id,v.shop_id,v.seller_sku,v.price_minor,v.compare_at_price_minor,
                       v.status variant_status,p.name product_name,p.slug product_slug,p.status product_status,
                       s.name shop_name,s.slug shop_slug,s.status shop_status,
                       coalesce(ss.default_shipping_fee_minor,30000) default_shipping_fee_minor,
                       b.quantity_on_hand-b.quantity_reserved available_quantity,
                       coalesce((select jsonb_object_agg(po.option_type,pov.code)::text
                           from variant_option_values x
                           join product_options po on po.id=x.option_id
                           join product_option_values pov on pov.id=x.value_id
                           where x.variant_id=v.id),'{}') options_json,
                       (select pi.storage_key from product_images pi
                           where pi.product_id=p.id and (pi.variant_id=v.id or pi.variant_id is null)
                           order by (pi.variant_id=v.id) desc nulls last,pi.is_primary desc,pi.sort_order,pi.created_at limit 1) image_key
                from cart_items ci
                join product_variants v on v.id=ci.variant_id
                join products p on p.id=v.product_id
                join shops s on s.id=v.shop_id
                join inventory_balances b on b.variant_id=v.id and b.shop_id=v.shop_id
                left join shop_settings ss on ss.shop_id=s.id
                where ci.cart_id=?
                order by s.name,p.name,v.seller_sku
                """, this::mapItem, cartId);
    }

    public Optional<VariantSaleRow> variantForCart(UUID variantId) {
        return jdbc.query("""
                select v.id variant_id,v.product_id,v.shop_id,v.price_minor,v.status variant_status,
                       p.status product_status,s.status shop_status,
                       b.quantity_on_hand-b.quantity_reserved available_quantity
                from product_variants v
                join products p on p.id=v.product_id
                join shops s on s.id=v.shop_id
                join inventory_balances b on b.variant_id=v.id and b.shop_id=v.shop_id
                where v.id=?
                """, (rs, row) -> new VariantSaleRow(
                rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getLong("price_minor"),
                rs.getString("variant_status"), rs.getString("product_status"),
                rs.getString("shop_status"), rs.getInt("available_quantity")), variantId).stream().findFirst();
    }

    public Optional<CartItemState> cartItem(UUID cartId, UUID itemId) {
        return jdbc.query("select id,variant_id,quantity,selected from cart_items where id=? and cart_id=?",
                (rs, row) -> new CartItemState(rs.getObject("id", UUID.class),
                        rs.getObject("variant_id", UUID.class), rs.getInt("quantity"), rs.getBoolean("selected")),
                itemId, cartId).stream().findFirst();
    }

    public Optional<CartItemState> cartItemByVariant(UUID cartId, UUID variantId) {
        return jdbc.query("select id,variant_id,quantity,selected from cart_items where cart_id=? and variant_id=?",
                (rs, row) -> new CartItemState(rs.getObject("id", UUID.class),
                        rs.getObject("variant_id", UUID.class), rs.getInt("quantity"), rs.getBoolean("selected")),
                cartId, variantId).stream().findFirst();
    }

    public int itemCount(UUID cartId) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from cart_items where cart_id=?",
                Integer.class, cartId));
    }

    public void insertCartItem(UUID cartId, UUID variantId, int quantity) {
        jdbc.update("insert into cart_items (cart_id,variant_id,quantity) values (?,?,?)", cartId, variantId, quantity);
    }

    public void updateCartItem(UUID itemId, int quantity, boolean selected) {
        jdbc.update("update cart_items set quantity=?,selected=? where id=?", quantity, selected, itemId);
    }

    public void deleteCartItem(UUID itemId) {
        jdbc.update("delete from cart_items where id=?", itemId);
    }

    public int updateSelection(UUID cartId, Collection<UUID> itemIds, boolean selected) {
        String placeholders = String.join(",", Collections.nCopies(itemIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(selected);
        args.add(cartId);
        args.addAll(itemIds);
        return jdbc.update("update cart_items set selected=? where cart_id=? and id in (" + placeholders + ")",
                args.toArray());
    }

    public boolean bumpCartVersion(UUID cartId, long expectedVersion) {
        return jdbc.update("update carts set version=version+1 where id=? and version=?", cartId, expectedVersion) == 1;
    }

    public Optional<AddressRow> address(UUID buyerId, UUID addressId) {
        return jdbc.query("""
                select id,user_id,recipient_name,recipient_phone,address_line1,address_line2,ward,district,
                       province,province_code,postal_code,country_code,is_default,created_at,updated_at
                from user_addresses where id=? and user_id=?
                """, this::mapAddress, addressId, buyerId).stream().findFirst();
    }

    public List<AddressRow> addresses(UUID buyerId) {
        return jdbc.query("""
                select id,user_id,recipient_name,recipient_phone,address_line1,address_line2,ward,district,
                       province,province_code,postal_code,country_code,is_default,created_at,updated_at
                from user_addresses where user_id=? order by is_default desc,created_at
                """, this::mapAddress, buyerId);
    }

    public UUID createAddress(UUID buyerId, CreateAddressRequest request, boolean defaultAddress) {
        if (defaultAddress) clearDefaultAddress(buyerId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into user_addresses
                    (id,user_id,recipient_name,recipient_phone,address_line1,address_line2,ward,district,
                     province,province_code,postal_code,country_code,is_default)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, buyerId, clean(request.recipientName()), clean(request.recipientPhone()),
                clean(request.addressLine1()), nullable(request.addressLine2()), nullable(request.ward()),
                nullable(request.district()), clean(request.province()), nullable(request.provinceCode()),
                nullable(request.postalCode()), request.countryCode(), defaultAddress);
        return id;
    }

    public void updateAddress(UUID buyerId, UUID addressId, UpdateAddressRequest request) {
        if (request.defaultAddress()) clearDefaultAddress(buyerId);
        jdbc.update("""
                update user_addresses set recipient_name=?,recipient_phone=?,address_line1=?,address_line2=?,
                    ward=?,district=?,province=?,province_code=?,postal_code=?,country_code=?,is_default=?
                where id=? and user_id=?
                """, clean(request.recipientName()), clean(request.recipientPhone()), clean(request.addressLine1()),
                nullable(request.addressLine2()), nullable(request.ward()), nullable(request.district()),
                clean(request.province()), nullable(request.provinceCode()), nullable(request.postalCode()),
                request.countryCode(), request.defaultAddress(), addressId, buyerId);
    }

    public void deleteAddress(UUID addressId) {
        jdbc.update("delete from user_addresses where id=?", addressId);
    }

    public int addressCount(UUID buyerId) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from user_addresses where user_id=?",
                Integer.class, buyerId));
    }

    public void makeOldestAddressDefault(UUID buyerId) {
        jdbc.update("""
                update user_addresses set is_default=true where id=(
                    select id from user_addresses where user_id=? order by created_at limit 1)
                """, buyerId);
    }

    public void expireReadySnapshots(UUID buyerId) {
        jdbc.update("update checkout_snapshots set status='EXPIRED' where buyer_id=? and status='READY'", buyerId);
    }

    public UUID createCheckoutSnapshot(UUID buyerId, UUID cartId, long cartVersion,
                                       RecipientSnapshot recipient, List<CheckoutShopSnapshot> shops,
                                       long itemsSubtotal, long shippingFee, long grandTotal, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into checkout_snapshots
                    (id,buyer_id,cart_id,cart_version,recipient_snapshot,items_snapshot,
                     items_subtotal_minor,shipping_fee_minor,discount_total_minor,grand_total_minor,expires_at)
                values (?,?,?,?,?::jsonb,?::jsonb,?,?,0,?,?)
                """, id, buyerId, cartId, cartVersion, json(recipient), json(shops),
                itemsSubtotal, shippingFee, grandTotal, java.sql.Timestamp.from(expiresAt));
        return id;
    }

    public Optional<CheckoutSnapshotRow> checkoutSnapshot(UUID buyerId, UUID snapshotId, boolean lock) {
        String locking = lock ? " for update" : "";
        return jdbc.query("""
                select id,buyer_id,cart_id,cart_version,currency,recipient_snapshot::text,
                       items_snapshot::text,items_subtotal_minor,shipping_fee_minor,
                       discount_total_minor,grand_total_minor,status,expires_at,consumed_at,
                       order_id,created_at
                from checkout_snapshots where id=? and buyer_id=?
                """ + locking, this::mapCheckoutSnapshot, snapshotId, buyerId).stream().findFirst();
    }

    public boolean expireCheckoutSnapshot(UUID snapshotId) {
        return jdbc.update("""
                update checkout_snapshots set status='EXPIRED'
                where id=? and status='READY' and expires_at<=now()
                """, snapshotId) == 1;
    }

    public boolean consumeCheckoutSnapshot(UUID snapshotId, UUID orderId) {
        return jdbc.update("""
                update checkout_snapshots set status='CONSUMED',consumed_at=now(),order_id=?
                where id=? and status='READY' and expires_at>now()
                """, orderId, snapshotId) == 1;
    }

    public int deleteCartItems(UUID cartId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(itemIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(cartId);
        args.addAll(itemIds);
        return jdbc.update("delete from cart_items where cart_id=? and id in (" + placeholders + ")",
                args.toArray());
    }

    private void clearDefaultAddress(UUID buyerId) {
        jdbc.update("update user_addresses set is_default=false where user_id=? and is_default", buyerId);
    }

    private CartRow mapCart(ResultSet rs, int row) throws SQLException {
        return new CartRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private CartItemRow mapItem(ResultSet rs, int row) throws SQLException {
        return new CartItemRow(rs.getObject("id", UUID.class), rs.getObject("cart_id", UUID.class),
                rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getString("shop_name"), rs.getString("shop_slug"),
                rs.getLong("default_shipping_fee_minor"), rs.getString("product_name"),
                rs.getString("product_slug"), rs.getString("seller_sku"), parseOptions(rs.getString("options_json")),
                rs.getString("image_key"), rs.getLong("price_minor"), nullableLong(rs, "compare_at_price_minor"),
                rs.getInt("quantity"), rs.getBoolean("selected"), rs.getInt("available_quantity"),
                rs.getString("variant_status"), rs.getString("product_status"), rs.getString("shop_status"));
    }

    private AddressRow mapAddress(ResultSet rs, int row) throws SQLException {
        return new AddressRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("recipient_name"), rs.getString("recipient_phone"), rs.getString("address_line1"),
                rs.getString("address_line2"), rs.getString("ward"), rs.getString("district"),
                rs.getString("province"), rs.getString("province_code"), rs.getString("postal_code"),
                rs.getString("country_code"), rs.getBoolean("is_default"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private CheckoutSnapshotRow mapCheckoutSnapshot(ResultSet rs, int row) throws SQLException {
        try {
            RecipientSnapshot recipient = mapper.readValue(rs.getString("recipient_snapshot"), RecipientSnapshot.class);
            List<CheckoutShopSnapshot> shops = mapper.readValue(rs.getString("items_snapshot"), new TypeReference<>() { });
            return new CheckoutSnapshotRow(rs.getObject("id", UUID.class), rs.getObject("buyer_id", UUID.class),
                    rs.getObject("cart_id", UUID.class), rs.getLong("cart_version"), rs.getString("currency"),
                    recipient, shops, rs.getLong("items_subtotal_minor"), rs.getLong("shipping_fee_minor"),
                    rs.getLong("discount_total_minor"), rs.getLong("grand_total_minor"), rs.getString("status"),
                    instant(rs, "expires_at"), nullableInstant(rs, "consumed_at"),
                    rs.getObject("order_id", UUID.class), instant(rs, "created_at"));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Invalid checkout snapshot JSON", ex);
        }
    }

    private Map<String, String> parseOptions(String value) {
        try { return mapper.readValue(value, new TypeReference<>() { }); }
        catch (JacksonException ex) { throw new IllegalStateException("Invalid variant options JSON", ex); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalStateException("Checkout snapshot is not serializable", ex); }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String clean(String value) { return value.strip(); }
    private static String nullable(String value) { return value == null || value.isBlank() ? null : value.strip(); }

    public record CartRow(UUID id, UUID buyerId, long version, Instant createdAt, Instant updatedAt) { }
    public record CartItemState(UUID id, UUID variantId, int quantity, boolean selected) { }
    public record VariantSaleRow(UUID variantId, UUID productId, UUID shopId, long priceMinor,
                                 String variantStatus, String productStatus, String shopStatus,
                                 int availableQuantity) {
        public boolean sellable() {
            return "ACTIVE".equals(variantStatus) && "ACTIVE".equals(productStatus) && "ACTIVE".equals(shopStatus);
        }
    }
    public record CartItemRow(
            UUID id, UUID cartId, UUID variantId, UUID productId, UUID shopId,
            String shopName, String shopSlug, long defaultShippingFeeMinor,
            String productName, String productSlug, String sellerSku, Map<String, String> options,
            String imageKey, long priceMinor, Long compareAtPriceMinor, int quantity, boolean selected,
            int availableQuantity, String variantStatus, String productStatus, String shopStatus) {
        public boolean sellable() {
            return "ACTIVE".equals(variantStatus) && "ACTIVE".equals(productStatus) && "ACTIVE".equals(shopStatus);
        }
    }
    public record AddressRow(
            UUID id, UUID buyerId, String recipientName, String recipientPhone,
            String addressLine1, String addressLine2, String ward, String district,
            String province, String provinceCode, String postalCode, String countryCode,
            boolean defaultAddress, Instant createdAt, Instant updatedAt) { }
    public record CheckoutSnapshotRow(
            UUID id, UUID buyerId, UUID cartId, long cartVersion, String currency,
            RecipientSnapshot recipient, List<CheckoutShopSnapshot> shops,
            long itemsSubtotalMinor, long shippingFeeMinor, long discountTotalMinor,
            long grandTotalMinor, String status, Instant expiresAt, Instant consumedAt,
            UUID orderId, Instant createdAt) { }
}
