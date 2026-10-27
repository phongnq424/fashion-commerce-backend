package com.fashioncommerce.fashioncommercebackend.common.response;

import java.util.List;

public record SliceResponse<T>(List<T> items, int limit, int offset, boolean hasMore) {
    public SliceResponse {
        items = List.copyOf(items);
    }
}

