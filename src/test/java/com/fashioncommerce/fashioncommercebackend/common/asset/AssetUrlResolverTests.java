package com.fashioncommerce.fashioncommercebackend.common.asset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssetUrlResolverTests {
    @Test
    void absoluteHttpUrlIsReturnedAsIs() {
        AssetUrlResolver resolver = new AssetUrlResolver("");

        assertThat(resolver.publicUrl("https://res.cloudinary.com/demo/image/upload/sample.jpg"))
                .isEqualTo("https://res.cloudinary.com/demo/image/upload/sample.jpg");
    }

    @Test
    void storageKeyIsJoinedWithConfiguredPublicBaseUrl() {
        AssetUrlResolver resolver = new AssetUrlResolver("https://cdn.example.com/assets/");

        assertThat(resolver.publicUrl("/products/main.jpg"))
                .isEqualTo("https://cdn.example.com/assets/products/main.jpg");
    }

    @Test
    void relativeKeyWithoutBaseUrlHasNoRenderableUrl() {
        AssetUrlResolver resolver = new AssetUrlResolver("");

        assertThat(resolver.publicUrl("products/main.jpg")).isNull();
    }

    @Test
    void unsafeSchemeIsNotExposed() {
        AssetUrlResolver resolver = new AssetUrlResolver("");

        assertThat(resolver.publicUrl("javascript:alert(1)")).isNull();
    }
}
