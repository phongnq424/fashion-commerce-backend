-- Product aggregate concurrency and public-catalog query indexes.

ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE products ADD CONSTRAINT ck_products_version CHECK (version >= 0);

CREATE INDEX ix_products_public_newest
    ON products (published_at DESC, id) WHERE status = 'ACTIVE';
CREATE INDEX ix_products_public_category_newest
    ON products (category_id, published_at DESC, id) WHERE status = 'ACTIVE';
CREATE INDEX ix_products_public_brand_newest
    ON products (brand_id, published_at DESC, id) WHERE status = 'ACTIVE';
CREATE INDEX ix_products_public_search
    ON products USING GIN (to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(description, '')))
    WHERE status = 'ACTIVE';
CREATE INDEX ix_product_variants_public_price
    ON product_variants (price_minor, product_id) WHERE status = 'ACTIVE';
