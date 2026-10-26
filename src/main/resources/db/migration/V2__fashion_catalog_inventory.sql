-- Fashion Commerce V1 | V2: fashion_catalog_inventory
-- PostgreSQL 18; require a clean, reviewed application schema before deployment.

-- Danh mục thời trang dạng cây (áo, quần, váy...).
CREATE TABLE categories (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    parent_id UUID,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(140) NOT NULL,
    description TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uq_categories_slug UNIQUE (slug),
    CONSTRAINT fk_categories_parent_id FOREIGN KEY (parent_id) REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT ck_categories_rule_1 CHECK (parent_id IS NULL OR parent_id <> id)
);
COMMENT ON TABLE categories IS 'Danh mục thời trang dạng cây (áo, quần, váy...).';
CREATE INDEX ix_categories_1 ON categories USING btree (parent_id, sort_order);

CREATE TRIGGER trg_categories_updated_at BEFORE UPDATE ON categories FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Danh mục thương hiệu sản phẩm.
CREATE TABLE brands (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(140) NOT NULL,
    logo_key VARCHAR(500),
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_brands PRIMARY KEY (id),
    CONSTRAINT uq_brands_slug UNIQUE (slug)
);
COMMENT ON TABLE brands IS 'Danh mục thương hiệu sản phẩm.';
CREATE INDEX ix_brands_1 ON brands USING btree (name);

CREATE TRIGGER trg_brands_updated_at BEFORE UPDATE ON brands FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- SP nội dung do một shop đăng, chưa phải SKU bán được.
CREATE TABLE products (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    shop_id UUID NOT NULL,
    category_id UUID NOT NULL,
    brand_id UUID,
    seller_product_code VARCHAR(80),
    name VARCHAR(240) NOT NULL,
    slug VARCHAR(260) NOT NULL,
    description TEXT NOT NULL,
    material VARCHAR(160),
    fit_type VARCHAR(80),
    target_gender VARCHAR(20) NOT NULL DEFAULT 'UNISEX',
    care_instructions TEXT,
    size_chart JSONB,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    submitted_at TIMESTAMPTZ,
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    rejection_reason TEXT,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_products PRIMARY KEY (id),
    CONSTRAINT uq_products_shop_id_slug UNIQUE (shop_id, slug),
    CONSTRAINT uq_products_id_shop_id UNIQUE (id, shop_id),
    CONSTRAINT fk_products_shop_id FOREIGN KEY (shop_id) REFERENCES shops (id) ON DELETE RESTRICT,
    CONSTRAINT fk_products_category_id FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_products_brand_id FOREIGN KEY (brand_id) REFERENCES brands (id) ON DELETE RESTRICT,
    CONSTRAINT fk_products_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_products_status_allowed CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_products_rule_2 CHECK (target_gender IN ('MALE','FEMALE','UNISEX','KIDS')),
    CONSTRAINT ck_products_rule_3 CHECK (size_chart IS NULL OR jsonb_typeof(size_chart) = 'object')
);
COMMENT ON TABLE products IS 'SP nội dung do một shop đăng, chưa phải SKU bán được.';
CREATE INDEX ix_products_1 ON products USING btree (shop_id, status);
CREATE INDEX ix_products_2 ON products USING btree (category_id, status);
CREATE INDEX ix_products_3 ON products USING btree (name);
CREATE INDEX ix_products_4 ON products USING btree (brand_id);
CREATE UNIQUE INDEX ix_products_5 ON products USING btree (shop_id, seller_product_code) WHERE seller_product_code IS NOT NULL;

CREATE TRIGGER trg_products_updated_at BEFORE UPDATE ON products FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Trục lựa chọn biến thể của một product; MVP dùng SIZE/COLOR.
CREATE TABLE product_options (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,
    option_type VARCHAR(16) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    sort_order SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_options PRIMARY KEY (id),
    CONSTRAINT uq_product_options_product_id_option_type UNIQUE (product_id, option_type),
    CONSTRAINT uq_product_options_id_product_id UNIQUE (id, product_id),
    CONSTRAINT fk_product_options_product_id FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_options_rule_1 CHECK (option_type IN ('SIZE','COLOR'))
);
COMMENT ON TABLE product_options IS 'Trục lựa chọn biến thể của một product; MVP dùng SIZE/COLOR.';

CREATE TRIGGER trg_product_options_updated_at BEFORE UPDATE ON product_options FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Các giá trị size/màu thuộc đúng option và product.
CREATE TABLE product_option_values (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    option_id UUID NOT NULL,
    product_id UUID NOT NULL,
    code VARCHAR(60) NOT NULL,
    label VARCHAR(100) NOT NULL,
    hex_color CHAR(7),
    sort_order SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_option_values PRIMARY KEY (id),
    CONSTRAINT uq_product_option_values_option_id_code UNIQUE (option_id, code),
    CONSTRAINT uq_product_option_values_id_option_id_product_id UNIQUE (id, option_id, product_id),
    CONSTRAINT fk_product_option_values_option_id_product_id FOREIGN KEY (option_id, product_id) REFERENCES product_options (id, product_id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_option_values_rule_1 CHECK (hex_color IS NULL OR hex_color ~ '^#[0-9A-Fa-f]{6}$')
);
COMMENT ON TABLE product_option_values IS 'Các giá trị size/màu thuộc đúng option và product.';
CREATE INDEX ix_product_option_values_1 ON product_option_values USING btree (product_id);

CREATE TRIGGER trg_product_option_values_updated_at BEFORE UPDATE ON product_option_values FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- SKU thực tế bán được: một kết hợp size/màu có giá và mã riêng.
CREATE TABLE product_variants (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    seller_sku VARCHAR(100) NOT NULL,
    barcode VARCHAR(100),
    option_signature VARCHAR(240) NOT NULL,
    price_minor BIGINT NOT NULL,
    compare_at_price_minor BIGINT,
    weight_grams INTEGER NOT NULL DEFAULT 300,
    length_mm INTEGER,
    width_mm INTEGER,
    height_mm INTEGER,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_variants PRIMARY KEY (id),
    CONSTRAINT uq_product_variants_shop_id_seller_sku UNIQUE (shop_id, seller_sku),
    CONSTRAINT uq_product_variants_product_id_option_signature UNIQUE (product_id, option_signature),
    CONSTRAINT uq_product_variants_id_product_id UNIQUE (id, product_id),
    CONSTRAINT uq_product_variants_id_shop_id UNIQUE (id, shop_id),
    CONSTRAINT uq_product_variants_id_product_id_shop_id UNIQUE (id, product_id, shop_id),
    CONSTRAINT fk_product_variants_product_id_shop_id FOREIGN KEY (product_id, shop_id) REFERENCES products (id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_product_variants_status_allowed CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_product_variants_rule_2 CHECK (price_minor >= 0 AND price_minor <= 1000000000000),
    CONSTRAINT ck_product_variants_rule_3 CHECK (compare_at_price_minor IS NULL OR compare_at_price_minor >= price_minor),
    CONSTRAINT ck_product_variants_rule_4 CHECK (weight_grams > 0),
    CONSTRAINT ck_product_variants_rule_5 CHECK (length_mm IS NULL OR length_mm > 0),
    CONSTRAINT ck_product_variants_rule_6 CHECK (width_mm IS NULL OR width_mm > 0),
    CONSTRAINT ck_product_variants_rule_7 CHECK (height_mm IS NULL OR height_mm > 0)
);
COMMENT ON TABLE product_variants IS 'SKU thực tế bán được: một kết hợp size/màu có giá và mã riêng.';
CREATE INDEX ix_product_variants_1 ON product_variants USING btree (product_id, status);

CREATE TRIGGER trg_product_variants_updated_at BEFORE UPDATE ON product_variants FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Giá trị SIZE/COLOR được chọn trên mỗi SKU; chặn option khác product.
CREATE TABLE variant_option_values (
    variant_id UUID NOT NULL,
    product_id UUID NOT NULL,
    option_id UUID NOT NULL,
    value_id UUID NOT NULL,
    CONSTRAINT pk_variant_option_values PRIMARY KEY (variant_id, option_id),
    CONSTRAINT fk_variant_option_values_variant_id_product_id FOREIGN KEY (variant_id, product_id) REFERENCES product_variants (id, product_id) ON DELETE CASCADE,
    CONSTRAINT fk_variant_option_values_option_id_product_id FOREIGN KEY (option_id, product_id) REFERENCES product_options (id, product_id) ON DELETE RESTRICT,
    CONSTRAINT fk_variant_option_values_value_id_option_id_product_id FOREIGN KEY (value_id, option_id, product_id) REFERENCES product_option_values (id, option_id, product_id) ON DELETE RESTRICT
);
COMMENT ON TABLE variant_option_values IS 'Giá trị SIZE/COLOR được chọn trên mỗi SKU; chặn option khác product.';
CREATE INDEX ix_variant_option_values_1 ON variant_option_values USING btree (value_id);

-- Ảnh sản phẩm/ảnh riêng cho biến thể; chỉ lưu storage key.
CREATE TABLE product_images (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL,
    variant_id UUID,
    storage_key VARCHAR(500) NOT NULL,
    alt_text VARCHAR(250),
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_product_images PRIMARY KEY (id),
    CONSTRAINT fk_product_images_product_id FOREIGN KEY (product_id) REFERENCES products (id) ON DELETE CASCADE,
    CONSTRAINT fk_product_images_variant_id_product_id FOREIGN KEY (variant_id, product_id) REFERENCES product_variants (id, product_id) ON DELETE CASCADE
);
COMMENT ON TABLE product_images IS 'Ảnh sản phẩm/ảnh riêng cho biến thể; chỉ lưu storage key.';
CREATE INDEX ix_product_images_1 ON product_images USING btree (product_id, sort_order);
CREATE INDEX ix_product_images_2 ON product_images USING btree (variant_id);
CREATE UNIQUE INDEX ix_product_images_3 ON product_images USING btree (product_id) WHERE is_primary=TRUE AND variant_id IS NULL;
CREATE UNIQUE INDEX ix_product_images_4 ON product_images USING btree (variant_id) WHERE is_primary=TRUE AND variant_id IS NOT NULL;

CREATE TRIGGER trg_product_images_updated_at BEFORE UPDATE ON product_images FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Tồn một kho mặc định cho mỗi SKU; reserved không vượt on_hand.
CREATE TABLE inventory_balances (
    variant_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    quantity_on_hand INTEGER NOT NULL DEFAULT 0,
    quantity_reserved INTEGER NOT NULL DEFAULT 0,
    reorder_point INTEGER NOT NULL DEFAULT 5,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_inventory_balances PRIMARY KEY (variant_id),
    CONSTRAINT uq_inventory_balances_variant_id_shop_id UNIQUE (variant_id, shop_id),
    CONSTRAINT fk_inventory_balances_variant_id_shop_id FOREIGN KEY (variant_id, shop_id) REFERENCES product_variants (id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_balances_rule_1 CHECK (quantity_on_hand >= 0),
    CONSTRAINT ck_inventory_balances_rule_2 CHECK (quantity_reserved >= 0),
    CONSTRAINT ck_inventory_balances_rule_3 CHECK (quantity_reserved <= quantity_on_hand),
    CONSTRAINT ck_inventory_balances_rule_4 CHECK (reorder_point >= 0),
    CONSTRAINT ck_inventory_balances_rule_5 CHECK (version >= 0)
);
COMMENT ON TABLE inventory_balances IS 'Tồn một kho mặc định cho mỗi SKU; reserved không vượt on_hand.';
CREATE INDEX ix_inventory_balances_1 ON inventory_balances USING btree (shop_id);

CREATE TRIGGER trg_inventory_balances_updated_at BEFORE UPDATE ON inventory_balances FOR EACH ROW EXECUTE FUNCTION fc_set_updated_at();

-- Sổ biến động tồn kho thực tế; không chứa reservation tăng/giảm.
CREATE TABLE inventory_movements (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    variant_id UUID NOT NULL,
    shop_id UUID NOT NULL,
    quantity_delta INTEGER NOT NULL,
    quantity_after INTEGER NOT NULL,
    reason VARCHAR(32) NOT NULL,
    reference_type VARCHAR(60),
    reference_id UUID,
    performed_by UUID,
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_inventory_movements PRIMARY KEY (id),
    CONSTRAINT fk_inventory_movements_variant_id_shop_id FOREIGN KEY (variant_id, shop_id) REFERENCES product_variants (id, shop_id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_movements_performed_by FOREIGN KEY (performed_by) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_inventory_movements_rule_1 CHECK (quantity_delta <> 0),
    CONSTRAINT ck_inventory_movements_rule_2 CHECK (quantity_after >= 0),
    CONSTRAINT ck_inventory_movements_rule_3 CHECK (reason IN ('STOCK_IN','SALE','RETURN_IN','ADJUSTMENT','DAMAGED'))
);
COMMENT ON TABLE inventory_movements IS 'Sổ biến động tồn kho thực tế; không chứa reservation tăng/giảm.';
CREATE INDEX ix_inventory_movements_1 ON inventory_movements USING btree (variant_id, created_at);
CREATE INDEX ix_inventory_movements_2 ON inventory_movements USING btree (shop_id, created_at);
