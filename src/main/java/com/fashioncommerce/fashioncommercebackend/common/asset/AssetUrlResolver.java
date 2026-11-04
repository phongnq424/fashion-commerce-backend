package com.fashioncommerce.fashioncommercebackend.common.asset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class AssetUrlResolver {
    private final String publicBaseUrl;

    public AssetUrlResolver(@Value("${assets.public-base-url:}") String publicBaseUrl) {
        this.publicBaseUrl = stripTrailingSlashes(publicBaseUrl == null ? "" : publicBaseUrl.strip());
    }

    public String publicUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return null;
        String value = storageKey.strip();
        if (isHttpUrl(value)) return value;
        if (publicBaseUrl.isEmpty()) return null;
        return publicBaseUrl + "/" + stripLeadingSlashes(value);
    }

    private static boolean isHttpUrl(String value) {
        try {
            String scheme = URI.create(value).getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String stripLeadingSlashes(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '/') index++;
        return value.substring(index);
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
}
