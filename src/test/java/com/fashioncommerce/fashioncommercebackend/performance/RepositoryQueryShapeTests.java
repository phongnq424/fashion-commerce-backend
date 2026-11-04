package com.fashioncommerce.fashioncommercebackend.performance;

import com.fashioncommerce.fashioncommercebackend.common.asset.AssetUrlResolver;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository;
import com.fashioncommerce.fashioncommercebackend.product.repository.ProductRepository.ProductRow;
import com.fashioncommerce.fashioncommercebackend.shop.repository.ShopRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryQueryShapeTests {
    @Test
    void publicProductDetailUsesFourFixedQueries() {
        ProductRow product = new ProductRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                null, "Product", "product", "Description", null, null, "UNISEX", null,
                Map.of(), 1, "ACTIVE", Instant.now(), UUID.randomUUID(), Instant.now(), Instant.now());
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(product);
        ProductRepository repository = new ProductRepository(jdbc, new ObjectMapper(), new AssetUrlResolver(""));

        assertThat(repository.publicProduct(product.id())).isPresent();
        assertThat(jdbc.queries).hasSize(4);
        assertThat(jdbc.queries.get(1)).contains("left join product_option_values");
        assertThat(jdbc.queries.get(2)).contains("left join variant_option_values");
        assertThat(jdbc.queries.get(3)).contains("from product_images");
    }

    @Test
    void memberAndInvitationListsAggregateRolesInTheirMainQuery() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(null);
        ShopRepository repository = new ShopRepository(jdbc, new ObjectMapper(), new AssetUrlResolver(""));

        repository.members(UUID.randomUUID(), 21, 0);
        assertThat(jdbc.queries).singleElement().asString().contains("array_agg(r.code");

        jdbc.queries.clear();
        repository.invitations(UUID.randomUUID());
        assertThat(jdbc.queries).singleElement().asString().contains("array_agg(r.code");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final ProductRow product;
        private final List<String> queries = new ArrayList<>();

        private RecordingJdbcTemplate(ProductRow product) {
            this.product = product;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            queries.add(sql);
            if (product != null && sql.contains("from products p join shops s")) {
                return (List<T>) List.of(product);
            }
            return List.of();
        }
    }
}
