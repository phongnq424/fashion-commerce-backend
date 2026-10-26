-- Fashion Commerce V1 | V3: cart_checkout_orders_reservations
-- PostgreSQL 18; require a clean, reviewed application schema before deployment.

-- Giỏ hàng đang hoạt động, một giỏ mỗi user.
CREATE TABLE carts (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_carts PRIMARY KEY (id),
    CONSTRAINT uq_carts_user_id UNIQUE (user_id),
    CONSTRAINT fk_carts_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_carts_rule_1 CHECK (version >= 0)
);
COMMENT ON TABLE carts IS 'Giỏ hàng đang hoạt động, một giỏ mỗi user.';

CREATE TRIGGER trg_carts_updated_at BEFORE UPDATE ON carts FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Các SKU và số lượng trong giỏ, giá được xác nhận tại checkout.
CREATE TABLE cart_items (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    cart_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_cart_items PRIMARY KEY (id),
    CONSTRAINT uq_cart_items_cart_id_variant_id UNIQUE (cart_id, variant_id),
    CONSTRAINT fk_cart_items_cart_id FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_variant_id FOREIGN KEY (variant_id) REFERENCES product_variants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cart_items_rule_1 CHECK (quantity BETWEEN 1 AND 99)
);
COMMENT ON TABLE cart_items IS 'Các SKU và số lượng trong giỏ, giá được xác nhận tại checkout.';
CREATE INDEX ix_cart_items_1 ON cart_items USING btree (variant_id);

CREATE TRIGGER trg_cart_items_updated_at BEFORE UPDATE ON cart_items FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Wishlist mặc định của mỗi người mua.
CREATE TABLE wishlists (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_wishlists PRIMARY KEY (id),
    CONSTRAINT uq_wishlists_user_id UNIQUE (user_id),
    CONSTRAINT fk_wishlists_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
COMMENT ON TABLE wishlists IS 'Wishlist mặc định của mỗi người mua.';

CREATE TRIGGER trg_wishlists_updated_at BEFORE UPDATE ON wishlists FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Danh sách sản phẩm (không phải SKU) người dùng lưu.
CREATE TABLE wishlist_items (
    wishlist_id UUID NOT NULL,
    product_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_wishlist_items PRIMARY KEY (wishlist_id, product_id),
    CONSTRAINT fk_wishlist_items_wishlist_id FOREIGN KEY (wishlist_id) REFERENCES wishlists (id) ON DELETE CASCADE,
    CONSTRAINT fk_wishlist_items_product_id FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT
);
COMMENT ON TABLE wishlist_items IS 'Danh sách sản phẩm (không phải SKU) người dùng lưu.';
CREATE INDEX ix_wishlist_items_1 ON wishlist_items USING btree (product_id);

-- Đơn tổng của buyer cho một checkout, chứa các shop_orders.
CREATE TABLE orders (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_number VARCHAR(32) NOT NULL,
    buyer_id UUID NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    recipient_snapshot JSONB NOT NULL,
    payment_method VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_PAYMENT',
    items_subtotal_minor BIGINT NOT NULL,
    shipping_fee_minor BIGINT NOT NULL,
    discount_total_minor BIGINT NOT NULL,
    grand_total_minor BIGINT NOT NULL,
    buyer_note VARCHAR(500),
    placed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    paid_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT uq_orders_id_buyer_id UNIQUE (id, buyer_id),
    CONSTRAINT fk_orders_buyer_id FOREIGN KEY (buyer_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_orders_status_allowed CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'PROCESSING', 'PARTIALLY_SHIPPED', 'SHIPPED', 'COMPLETED', 'CANCELLED', 'PARTIALLY_CANCELLED')),
    CONSTRAINT ck_orders_rule_2 CHECK (currency = 'VND'),
    CONSTRAINT ck_orders_rule_3 CHECK (payment_method IN ('COD','ONLINE')),
    CONSTRAINT ck_orders_rule_4 CHECK (jsonb_typeof(recipient_snapshot)='object'),
    CONSTRAINT ck_orders_rule_5 CHECK (items_subtotal_minor >= 0),
    CONSTRAINT ck_orders_rule_6 CHECK (shipping_fee_minor >= 0),
    CONSTRAINT ck_orders_rule_7 CHECK (discount_total_minor >= 0),
    CONSTRAINT ck_orders_rule_8 CHECK (discount_total_minor <= items_subtotal_minor + shipping_fee_minor),
    CONSTRAINT ck_orders_rule_9 CHECK (grand_total_minor = items_subtotal_minor + shipping_fee_minor - discount_total_minor)
);
COMMENT ON TABLE orders IS 'Đơn tổng của buyer cho một checkout, chứa các shop_orders.';
CREATE INDEX ix_orders_1 ON orders USING btree (buyer_id, created_at);
CREATE INDEX ix_orders_2 ON orders USING btree (status, created_at);

CREATE TRIGGER trg_orders_updated_at BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Đơn con tách theo shop, mỗi shop tối đa một đơn con trong checkout.
CREATE TABLE shop_orders (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    shop_order_number VARCHAR(40) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_PAYMENT',
    items_subtotal_minor BIGINT NOT NULL,
    shipping_fee_minor BIGINT NOT NULL,
    discount_total_minor BIGINT NOT NULL,
    total_minor BIGINT NOT NULL,
    seller_note VARCHAR(500),
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_shop_orders PRIMARY KEY (id),
    CONSTRAINT uq_shop_orders_shop_order_number UNIQUE (shop_order_number),
    CONSTRAINT uq_shop_orders_order_id_shop_id UNIQUE (order_id, shop_id),
    CONSTRAINT uq_shop_orders_id_order_id_shop_id UNIQUE (id, order_id, shop_id),
    CONSTRAINT uq_shop_orders_id_order_id UNIQUE (id, order_id),
    CONSTRAINT fk_shop_orders_order_id FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_shop_orders_shop_id FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shop_orders_status_allowed CHECK (status IN ('PENDING_PAYMENT', 'PENDING_CONFIRMATION', 'PROCESSING', 'READY_TO_SHIP', 'SHIPPED', 'DELIVERED', 'COMPLETED', 'CANCELLED', 'RETURNED')),
    CONSTRAINT ck_shop_orders_rule_2 CHECK (items_subtotal_minor >= 0),
    CONSTRAINT ck_shop_orders_rule_3 CHECK (shipping_fee_minor >= 0),
    CONSTRAINT ck_shop_orders_rule_4 CHECK (discount_total_minor >= 0),
    CONSTRAINT ck_shop_orders_rule_5 CHECK (discount_total_minor <= items_subtotal_minor + shipping_fee_minor),
    CONSTRAINT ck_shop_orders_rule_6 CHECK (total_minor = items_subtotal_minor + shipping_fee_minor - discount_total_minor)
);
COMMENT ON TABLE shop_orders IS 'Đơn con tách theo shop, mỗi shop tối đa một đơn con trong checkout.';
CREATE INDEX ix_shop_orders_1 ON shop_orders USING btree (shop_id, status, created_at);
CREATE INDEX ix_shop_orders_2 ON shop_orders USING btree (order_id);

CREATE TRIGGER trg_shop_orders_updated_at BEFORE UPDATE ON shop_orders FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Dòng hàng bất biến theo giá/sku/tên/size/màu tại thời điểm mua.
CREATE TABLE order_items (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_order_id UUID NOT NULL,
    order_id UUID NOT NULL,
    buyer_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    product_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    seller_sku_snapshot VARCHAR(100) NOT NULL,
    product_name_snapshot VARCHAR(240) NOT NULL,
    options_snapshot JSONB NOT NULL,
    unit_price_minor BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    discount_minor BIGINT NOT NULL,
    line_total_minor BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT uq_order_items_shop_order_id_variant_id UNIQUE (shop_order_id, variant_id),
    CONSTRAINT uq_order_items_id_buyer_id_product_id_shop_id UNIQUE (id, buyer_id, product_id, shop_id),
    CONSTRAINT uq_order_items_id_shop_order_id UNIQUE (id, shop_order_id),
    CONSTRAINT uq_order_items_id_variant_id_shop_id UNIQUE (id, variant_id, shop_id),
    CONSTRAINT uq_order_items_id_variant_id_shop_id_quantity UNIQUE (id, variant_id, shop_id, quantity),
    CONSTRAINT fk_order_items_shop_order_id_order_id_shop_id FOREIGN KEY (shop_order_id, order_id, shop_id) REFERENCES shop_orders (id, order_id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_order_id_buyer_id FOREIGN KEY (order_id, buyer_id) REFERENCES orders (id, buyer_id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_variant_id_product_id_shop_id FOREIGN KEY (variant_id, product_id, shop_id) REFERENCES product_variants (id, product_id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_order_items_rule_1 CHECK (jsonb_typeof(options_snapshot) = 'object'),
    CONSTRAINT ck_order_items_rule_2 CHECK (unit_price_minor >= 0),
    CONSTRAINT ck_order_items_rule_3 CHECK (quantity BETWEEN 1 AND 99),
    CONSTRAINT ck_order_items_rule_4 CHECK (discount_minor >= 0),
    CONSTRAINT ck_order_items_rule_5 CHECK (discount_minor <= unit_price_minor * quantity),
    CONSTRAINT ck_order_items_rule_6 CHECK (line_total_minor = unit_price_minor * quantity - discount_minor)
);
COMMENT ON TABLE order_items IS 'Dòng hàng bất biến theo giá/sku/tên/size/màu tại thời điểm mua.';
CREATE INDEX ix_order_items_1 ON order_items USING btree (order_id);
CREATE INDEX ix_order_items_2 ON order_items USING btree (product_id);
CREATE INDEX ix_order_items_3 ON order_items USING btree (buyer_id);

-- Nhật ký trạng thái của đơn tổng hoặc đơn shop (đúng một loại).
CREATE TABLE order_status_history (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_id UUID,
    shop_order_id UUID,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_user_id UUID,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_order_status_history PRIMARY KEY (id),
    CONSTRAINT fk_order_status_history_order_id FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_status_history_shop_order_id FOREIGN KEY (shop_order_id) REFERENCES shop_orders (id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_status_history_actor_user_id FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_order_status_history_rule_1 CHECK (num_nonnulls(order_id,shop_order_id)=1)
);
COMMENT ON TABLE order_status_history IS 'Nhật ký trạng thái của đơn tổng hoặc đơn shop (đúng một loại).';
CREATE INDEX ix_order_status_history_1 ON order_status_history USING btree (order_id, created_at);
CREATE INDEX ix_order_status_history_2 ON order_status_history USING btree (shop_order_id, created_at);

-- Giữ hàng theo order_item trước thanh toán; có thời hạn và giải phóng.
CREATE TABLE inventory_reservations (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    order_item_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
    expires_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_inventory_reservations PRIMARY KEY (id),
    CONSTRAINT uq_inventory_reservations_order_item_id UNIQUE (order_item_id),
    CONSTRAINT fk_inventory_reservations_variant_id FOREIGN KEY (variant_id) REFERENCES inventory_balances (variant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_reservations_variant_id_shop_id FOREIGN KEY (variant_id, shop_id) REFERENCES inventory_balances (variant_id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_reservations_order_item_id_variant__0a3f06d8 FOREIGN KEY (order_item_id, variant_id, shop_id, quantity) REFERENCES order_items (id, variant_id, shop_id, quantity) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_reservations_status_allowed CHECK (status IN ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_inventory_reservations_rule_2 CHECK (quantity > 0)
);
COMMENT ON TABLE inventory_reservations IS 'Giữ hàng theo order_item trước thanh toán; có thời hạn và giải phóng.';
CREATE INDEX ix_inventory_reservations_1 ON inventory_reservations USING btree (status, expires_at);
CREATE INDEX ix_inventory_reservations_2 ON inventory_reservations USING btree (variant_id, status);

CREATE TRIGGER trg_inventory_reservations_updated_at BEFORE UPDATE ON inventory_reservations FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();
