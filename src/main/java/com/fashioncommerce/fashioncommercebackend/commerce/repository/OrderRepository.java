package com.fashioncommerce.fashioncommercebackend.commerce.repository;

import com.fashioncommerce.fashioncommercebackend.commerce.dto.CommerceDtos.*;
import com.fashioncommerce.fashioncommercebackend.commerce.payment.PaymentMethodStrategy.InventoryMode;
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
public class OrderRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OrderRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public IdempotencyRow claimIdempotency(UUID buyerId, String key, String requestHash) {
        jdbc.update("""
                insert into order_idempotency_keys (buyer_id,idempotency_key,request_hash)
                values (?,?,?) on conflict (buyer_id,idempotency_key) do nothing
                """, buyerId, key, requestHash);
        return jdbc.query("""
                select buyer_id,idempotency_key,request_hash,order_id,created_at,expires_at
                from order_idempotency_keys where buyer_id=? and idempotency_key=? for update
                """, this::mapIdempotency, buyerId, key).stream().findFirst().orElseThrow();
    }

    public void completeIdempotency(UUID buyerId, String key, UUID orderId) {
        if (jdbc.update("""
                update order_idempotency_keys set order_id=?
                where buyer_id=? and idempotency_key=? and order_id is null
                """, orderId, buyerId, key) != 1) {
            throw new IllegalStateException("Order idempotency claim was lost");
        }
    }

    public List<VariantOrderRow> lockVariants(Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(variantIds.size(), "?"));
        return jdbc.query("""
                select v.id variant_id,v.product_id,v.shop_id,v.price_minor,
                       v.status variant_status,p.status product_status,s.status shop_status,
                       b.quantity_on_hand,b.quantity_reserved
                from product_variants v
                join products p on p.id=v.product_id
                join shops s on s.id=v.shop_id
                join inventory_balances b on b.variant_id=v.id and b.shop_id=v.shop_id
                where v.id in (""" + placeholders + ") order by v.id for update of b",
                this::mapVariant, variantIds.toArray());
    }

    public void insertOrder(NewOrder order) {
        jdbc.update("""
                insert into orders
                    (id,order_number,buyer_id,currency,recipient_snapshot,payment_method,status,
                     items_subtotal_minor,shipping_fee_minor,discount_total_minor,grand_total_minor,buyer_note)
                values (?,?,?,?,?::jsonb,?,?,?,?,?,?,?)
                """, order.id(), order.orderNumber(), order.buyerId(), order.currency(), json(order.recipient()),
                order.paymentMethod().name(), order.status(), order.itemsSubtotalMinor(), order.shippingFeeMinor(),
                order.discountTotalMinor(), order.grandTotalMinor(), nullable(order.buyerNote()));
        insertOrderHistory(order.id(), null, null, order.status(), order.buyerId(), "Order placed");
    }

    public void insertShopOrders(List<NewShopOrder> shops) {
        jdbc.batchUpdate("""
                insert into shop_orders
                    (id,order_id,shop_id,shop_order_number,status,items_subtotal_minor,
                     shipping_fee_minor,discount_total_minor,total_minor)
                values (?,?,?,?,?,?,?,?,?)
                """, shops.stream().map(shop -> new Object[]{shop.id(), shop.orderId(), shop.shopId(),
                shop.shopOrderNumber(), shop.status(), shop.itemsSubtotalMinor(), shop.shippingFeeMinor(),
                shop.discountTotalMinor(), shop.totalMinor()}).toList());
        jdbc.batchUpdate("""
                insert into order_status_history (shop_order_id,from_status,to_status,actor_user_id,note)
                values (?,null,?,?,?)
                """, shops.stream().map(shop -> new Object[]{shop.id(), shop.status(), shop.buyerId(),
                "Order placed"}).toList());
    }

    public void insertOrderItems(List<NewOrderItem> items) {
        jdbc.batchUpdate("""
                insert into order_items
                    (id,shop_order_id,order_id,buyer_id,shop_id,product_id,variant_id,
                     seller_sku_snapshot,product_name_snapshot,options_snapshot,unit_price_minor,
                     quantity,discount_minor,line_total_minor)
                values (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?)
                """, items.stream().map(item -> new Object[]{item.id(), item.shopOrderId(), item.orderId(),
                item.buyerId(), item.shopId(), item.productId(), item.variantId(), item.sellerSku(),
                item.productName(), json(item.options()), item.unitPriceMinor(), item.quantity(),
                item.discountMinor(), item.lineTotalMinor()}).toList());
    }

    public void applyInventory(List<NewOrderItem> items, Map<UUID, VariantOrderRow> lockedVariants,
                               InventoryMode mode, Instant expiresAt, Instant now) {
        if (mode == InventoryMode.RESERVE && (expiresAt == null || !expiresAt.isAfter(now))) {
            throw new IllegalArgumentException("Reserved inventory requires a future expiry");
        }
        List<Object[]> balanceArgs = new ArrayList<>();
        for (NewOrderItem item : items) {
            balanceArgs.add(new Object[]{item.quantity(), item.variantId(), item.quantity()});
        }
        String balanceSql = mode == InventoryMode.COMMIT
                ? "update inventory_balances set quantity_on_hand=quantity_on_hand-?,version=version+1 "
                    + "where variant_id=? and quantity_on_hand-quantity_reserved>=?"
                : "update inventory_balances set quantity_reserved=quantity_reserved+?,version=version+1 "
                    + "where variant_id=? and quantity_on_hand-quantity_reserved>=?";
        int[] counts = jdbc.batchUpdate(balanceSql, balanceArgs);
        for (int count : counts) if (count != 1) {
            throw new IllegalStateException("Inventory changed while locked");
        }

        jdbc.batchUpdate("""
                insert into inventory_reservations
                    (id,order_item_id,variant_id,shop_id,quantity,status,expires_at,finalized_at)
                values (?,?,?,?,?,?,?,?)
                """, items.stream().map(item -> new Object[]{UUID.randomUUID(), item.id(), item.variantId(),
                item.shopId(), item.quantity(), mode == InventoryMode.COMMIT ? "COMMITTED" : "RESERVED",
                java.sql.Timestamp.from(mode == InventoryMode.COMMIT ? now : expiresAt),
                mode == InventoryMode.COMMIT ? java.sql.Timestamp.from(now) : null}).toList());

        if (mode == InventoryMode.COMMIT) {
            jdbc.batchUpdate("""
                    insert into inventory_movements
                        (variant_id,shop_id,quantity_delta,quantity_after,reason,reference_type,
                         reference_id,performed_by,note)
                    values (?,?,?,?, 'SALE','ORDER_ITEM',?,?, 'Committed at order placement')
                    """, items.stream().map(item -> {
                VariantOrderRow before = lockedVariants.get(item.variantId());
                return new Object[]{item.variantId(), item.shopId(), -item.quantity(),
                        before.quantityOnHand() - item.quantity(), item.id(), item.buyerId()};
            }).toList());
        }
    }

    public void insertPaymentIntent(UUID orderId, String merchantReference, PaymentMethod method,
                                    String provider, long amountMinor, String currency, String status,
                                    String providerPaymentId, String checkoutUrl, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into payment_intents
                    (id,order_id,merchant_reference,method,provider,provider_payment_id,amount_minor,
                     currency,status,checkout_url,expires_at)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """, id, orderId, merchantReference, method.name(), provider, providerPaymentId,
                amountMinor, currency, status, checkoutUrl, timestamp(expiresAt));
    }

    public Optional<OrderResponse> order(UUID buyerId, UUID orderId) {
        Optional<OrderRow> order = jdbc.query("""
                select id,order_number,buyer_id,currency,recipient_snapshot::text,payment_method,status,
                       items_subtotal_minor,shipping_fee_minor,discount_total_minor,grand_total_minor,
                       buyer_note,placed_at,paid_at,completed_at,cancelled_at
                from orders where id=? and buyer_id=?
                """, this::mapOrder, orderId, buyerId).stream().findFirst();
        return order.map(this::assemble);
    }

    public List<OrderSummaryResponse> orderSummaries(UUID buyerId, int limitPlusOne, int offset) {
        return jdbc.query("""
                select o.id,o.order_number,o.status,o.payment_method,o.grand_total_minor,o.currency,o.placed_at,
                       (select count(*) from shop_orders so where so.order_id=o.id) shop_count,
                       (select count(*) from order_items oi where oi.order_id=o.id) item_count,
                       (select pi.status from payment_intents pi where pi.order_id=o.id
                        order by pi.created_at desc limit 1) payment_status
                from orders o where o.buyer_id=? order by o.placed_at desc,o.id desc limit ? offset ?
                """, (rs, row) -> new OrderSummaryResponse(rs.getObject("id", UUID.class),
                rs.getString("order_number"), rs.getString("status"),
                PaymentMethod.valueOf(rs.getString("payment_method")), rs.getLong("grand_total_minor"),
                rs.getString("currency"), rs.getInt("shop_count"), rs.getInt("item_count"),
                rs.getString("payment_status"), instant(rs, "placed_at")), buyerId, limitPlusOne, offset);
    }

    public Optional<PaymentResponse> paymentForBuyer(UUID buyerId, UUID paymentId) {
        return jdbc.query("""
                select pi.id,pi.order_id,pi.merchant_reference,pi.method,pi.provider,pi.amount_minor,
                       pi.currency,pi.status,pi.checkout_url,pi.expires_at,pi.paid_at,pi.created_at
                from payment_intents pi join orders o on o.id=pi.order_id
                where pi.id=? and o.buyer_id=?
                """, this::mapPaymentResponse, paymentId, buyerId).stream().findFirst();
    }

    public Optional<OrderRow> orderForUpdate(UUID buyerId, UUID orderId) {
        return jdbc.query("""
                select id,order_number,buyer_id,currency,recipient_snapshot::text,payment_method,status,
                       items_subtotal_minor,shipping_fee_minor,discount_total_minor,grand_total_minor,
                       buyer_note,placed_at,paid_at,completed_at,cancelled_at
                from orders where id=? and buyer_id=? for update
                """, this::mapOrder, orderId, buyerId).stream().findFirst();
    }

    public List<ReservationRow> reservationsForUpdate(UUID orderId) {
        return jdbc.query("""
                select r.id,r.order_item_id,r.variant_id,r.shop_id,r.quantity,r.status,
                       b.quantity_on_hand,b.quantity_reserved
                from inventory_reservations r
                join order_items oi on oi.id=r.order_item_id
                join inventory_balances b on b.variant_id=r.variant_id and b.shop_id=r.shop_id
                where oi.order_id=? order by r.variant_id for update of r,b
                """, (rs, row) -> new ReservationRow(rs.getObject("id", UUID.class),
                rs.getObject("order_item_id", UUID.class), rs.getObject("variant_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getInt("quantity"), rs.getString("status"),
                rs.getInt("quantity_on_hand"), rs.getInt("quantity_reserved")), orderId);
    }

    public boolean allShopOrdersCancellable(UUID orderId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select not exists (
                    select 1 from shop_orders where order_id=?
                    and status not in ('PENDING_PAYMENT','PENDING_CONFIRMATION','CANCELLED'))
                """, Boolean.class, orderId));
    }

    public void releaseInventoryForCancellation(List<ReservationRow> reservations, UUID buyerId) {
        for (ReservationRow reservation : reservations) {
            if ("COMMITTED".equals(reservation.status())) {
                jdbc.update("""
                        update inventory_balances set quantity_on_hand=quantity_on_hand+?,version=version+1
                        where variant_id=?
                        """, reservation.quantity(), reservation.variantId());
                jdbc.update("""
                        insert into inventory_movements
                            (variant_id,shop_id,quantity_delta,quantity_after,reason,reference_type,
                             reference_id,performed_by,note)
                        values (?,?,?,?, 'RETURN_IN','ORDER_ITEM',?,?, 'Restocked after order cancellation')
                        """, reservation.variantId(), reservation.shopId(), reservation.quantity(),
                        reservation.quantityOnHand() + reservation.quantity(), reservation.orderItemId(), buyerId);
                jdbc.update("""
                        update inventory_reservations set status='RESTOCKED',finalized_at=now()
                        where id=? and status='COMMITTED'
                        """, reservation.id());
            } else if ("RESERVED".equals(reservation.status())) {
                jdbc.update("""
                        update inventory_balances set quantity_reserved=quantity_reserved-?,version=version+1
                        where variant_id=? and quantity_reserved>=?
                        """, reservation.quantity(), reservation.variantId(), reservation.quantity());
                jdbc.update("""
                        update inventory_reservations set status='RELEASED',finalized_at=now()
                        where id=? and status='RESERVED'
                        """, reservation.id());
            }
        }
    }

    public void cancelOrder(UUID orderId, UUID buyerId, String fromStatus, String reason) {
        jdbc.update("""
                insert into order_status_history
                    (shop_order_id,from_status,to_status,actor_user_id,note)
                select id,status,'CANCELLED',?,? from shop_orders
                where order_id=? and status in ('PENDING_PAYMENT','PENDING_CONFIRMATION')
                """, buyerId, nullable(reason), orderId);
        jdbc.update("""
                update shop_orders set status='CANCELLED',cancelled_at=now()
                where order_id=? and status in ('PENDING_PAYMENT','PENDING_CONFIRMATION')
                """, orderId);
        jdbc.update("""
                update payment_intents set status='CANCELLED'
                where order_id=? and status in ('CREATED','PENDING','AWAITING_COLLECTION')
                """, orderId);
        if (jdbc.update("""
                update orders set status='CANCELLED',cancelled_at=now()
                where id=? and status=?
                """, orderId, fromStatus) != 1) {
            throw new IllegalStateException("Order state changed while locked");
        }
        insertOrderHistory(orderId, null, fromStatus, "CANCELLED", buyerId, reason);
    }

    private OrderResponse assemble(OrderRow order) {
        List<ShopOrderRow> shopRows = shopOrders(order.id());
        Map<UUID, List<OrderItemResponse>> items = orderItems(order.id()).stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderItemWithShop::shopOrderId,
                        LinkedHashMap::new, java.util.stream.Collectors.mapping(OrderItemWithShop::item,
                                java.util.stream.Collectors.toList())));
        List<ShopOrderResponse> shops = shopRows.stream().map(shop -> new ShopOrderResponse(
                shop.id(), shop.shopId(), shop.number(), shop.status(), shop.itemsSubtotalMinor(),
                shop.shippingFeeMinor(), shop.discountTotalMinor(), shop.totalMinor(),
                items.getOrDefault(shop.id(), List.of()))).toList();
        PaymentResponse payment = paymentByOrder(order.id()).map(this::paymentResponse).orElse(null);
        return new OrderResponse(order.id(), order.orderNumber(), order.status(), order.currency(),
                order.paymentMethod(), order.recipient(), order.itemsSubtotalMinor(), order.shippingFeeMinor(),
                order.discountTotalMinor(), order.grandTotalMinor(), order.buyerNote(), order.placedAt(),
                order.paidAt(), order.completedAt(), order.cancelledAt(), shops, payment);
    }

    private List<ShopOrderRow> shopOrders(UUID orderId) {
        return jdbc.query("""
                select id,shop_id,shop_order_number,status,items_subtotal_minor,shipping_fee_minor,
                       discount_total_minor,total_minor from shop_orders where order_id=? order by created_at,id
                """, (rs, row) -> new ShopOrderRow(rs.getObject("id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getString("shop_order_number"), rs.getString("status"),
                rs.getLong("items_subtotal_minor"), rs.getLong("shipping_fee_minor"),
                rs.getLong("discount_total_minor"), rs.getLong("total_minor")), orderId);
    }

    private List<OrderItemWithShop> orderItems(UUID orderId) {
        return jdbc.query("""
                select id,shop_order_id,product_id,variant_id,seller_sku_snapshot,product_name_snapshot,
                       options_snapshot::text,unit_price_minor,quantity,discount_minor,line_total_minor
                from order_items where order_id=? order by shop_order_id,created_at,id
                """, (rs, row) -> new OrderItemWithShop(rs.getObject("shop_order_id", UUID.class),
                new OrderItemResponse(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class),
                        rs.getObject("variant_id", UUID.class), rs.getString("seller_sku_snapshot"),
                        rs.getString("product_name_snapshot"), options(rs.getString("options_snapshot")),
                        rs.getLong("unit_price_minor"), rs.getInt("quantity"),
                        rs.getLong("discount_minor"), rs.getLong("line_total_minor"))), orderId);
    }

    private Optional<PaymentRow> paymentByOrder(UUID orderId) {
        return jdbc.query("""
                select id,order_id,merchant_reference,method,provider,amount_minor,currency,status,
                       checkout_url,expires_at,paid_at,created_at from payment_intents
                where order_id=? order by created_at desc limit 1
                """, this::mapPayment, orderId).stream().findFirst();
    }

    private void insertOrderHistory(UUID orderId, UUID shopOrderId, String fromStatus, String toStatus,
                                    UUID actor, String note) {
        jdbc.update("""
                insert into order_status_history
                    (order_id,shop_order_id,from_status,to_status,actor_user_id,note)
                values (?,?,?,?,?,?)
                """, orderId, shopOrderId, fromStatus, toStatus, actor, nullable(note));
    }

    private IdempotencyRow mapIdempotency(ResultSet rs, int row) throws SQLException {
        return new IdempotencyRow(rs.getObject("buyer_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("request_hash"), rs.getObject("order_id", UUID.class),
                instant(rs, "created_at"), instant(rs, "expires_at"));
    }

    private VariantOrderRow mapVariant(ResultSet rs, int row) throws SQLException {
        return new VariantOrderRow(rs.getObject("variant_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("shop_id", UUID.class), rs.getLong("price_minor"), rs.getString("variant_status"),
                rs.getString("product_status"), rs.getString("shop_status"), rs.getInt("quantity_on_hand"),
                rs.getInt("quantity_reserved"));
    }

    private OrderRow mapOrder(ResultSet rs, int row) throws SQLException {
        try {
            return new OrderRow(rs.getObject("id", UUID.class), rs.getString("order_number"),
                    rs.getObject("buyer_id", UUID.class), rs.getString("currency"),
                    mapper.readValue(rs.getString("recipient_snapshot"), RecipientSnapshot.class),
                    PaymentMethod.valueOf(rs.getString("payment_method")), rs.getString("status"),
                    rs.getLong("items_subtotal_minor"), rs.getLong("shipping_fee_minor"),
                    rs.getLong("discount_total_minor"), rs.getLong("grand_total_minor"),
                    rs.getString("buyer_note"), instant(rs, "placed_at"), nullableInstant(rs, "paid_at"),
                    nullableInstant(rs, "completed_at"), nullableInstant(rs, "cancelled_at"));
        } catch (JacksonException ex) {
            throw new IllegalStateException("Invalid order recipient snapshot", ex);
        }
    }

    private PaymentRow mapPayment(ResultSet rs, int row) throws SQLException {
        return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getString("merchant_reference"), PaymentMethod.valueOf(rs.getString("method")),
                rs.getString("provider"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("status"), rs.getString("checkout_url"), nullableInstant(rs, "expires_at"),
                nullableInstant(rs, "paid_at"), instant(rs, "created_at"));
    }

    private PaymentResponse mapPaymentResponse(ResultSet rs, int row) throws SQLException {
        return paymentResponse(mapPayment(rs, row));
    }

    private PaymentResponse paymentResponse(PaymentRow row) {
        return new PaymentResponse(row.id(), row.orderId(), row.merchantReference(), row.method(), row.provider(),
                row.amountMinor(), row.currency(), row.status(), row.checkoutUrl(), row.expiresAt(),
                row.paidAt(), row.createdAt());
    }

    private Map<String, String> options(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (JacksonException ex) { throw new IllegalStateException("Invalid order options snapshot", ex); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException ex) { throw new IllegalStateException("Order snapshot is not serializable", ex); }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static java.sql.Timestamp timestamp(Instant value) {
        return value == null ? null : java.sql.Timestamp.from(value);
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record IdempotencyRow(UUID buyerId, String key, String requestHash, UUID orderId,
                                 Instant createdAt, Instant expiresAt) { }
    public record VariantOrderRow(UUID variantId, UUID productId, UUID shopId, long priceMinor,
                                  String variantStatus, String productStatus, String shopStatus,
                                  int quantityOnHand, int quantityReserved) {
        public int availableQuantity() { return quantityOnHand - quantityReserved; }
        public boolean sellable() {
            return "ACTIVE".equals(variantStatus) && "ACTIVE".equals(productStatus) && "ACTIVE".equals(shopStatus);
        }
    }
    public record NewOrder(UUID id, String orderNumber, UUID buyerId, String currency,
                           RecipientSnapshot recipient, PaymentMethod paymentMethod, String status,
                           long itemsSubtotalMinor, long shippingFeeMinor, long discountTotalMinor,
                           long grandTotalMinor, String buyerNote) { }
    public record NewShopOrder(UUID id, UUID orderId, UUID shopId, String shopOrderNumber, String status,
                               long itemsSubtotalMinor, long shippingFeeMinor, long discountTotalMinor,
                               long totalMinor, UUID buyerId) { }
    public record NewOrderItem(UUID id, UUID shopOrderId, UUID orderId, UUID buyerId, UUID shopId,
                               UUID productId, UUID variantId, String sellerSku, String productName,
                               Map<String, String> options, long unitPriceMinor, int quantity,
                               long discountMinor, long lineTotalMinor) { }
    public record OrderRow(UUID id, String orderNumber, UUID buyerId, String currency,
                           RecipientSnapshot recipient, PaymentMethod paymentMethod, String status,
                           long itemsSubtotalMinor, long shippingFeeMinor, long discountTotalMinor,
                           long grandTotalMinor, String buyerNote, Instant placedAt, Instant paidAt,
                           Instant completedAt, Instant cancelledAt) { }
    public record PaymentRow(UUID id, UUID orderId, String merchantReference, PaymentMethod method,
                             String provider, long amountMinor, String currency, String status,
                             String checkoutUrl, Instant expiresAt, Instant paidAt, Instant createdAt) { }
    public record ReservationRow(UUID id, UUID orderItemId, UUID variantId, UUID shopId, int quantity,
                                 String status, int quantityOnHand, int quantityReserved) { }
    private record ShopOrderRow(UUID id, UUID shopId, String number, String status,
                                long itemsSubtotalMinor, long shippingFeeMinor,
                                long discountTotalMinor, long totalMinor) { }
    private record OrderItemWithShop(UUID shopOrderId, OrderItemResponse item) { }
}
