package com.fashioncommerce.fashioncommercebackend.common.response;

import java.util.List;

public record CursorPageResponse<T>(List<T> items, int limit, String nextCursor, boolean hasMore) {
    public CursorPageResponse {
        items = List.copyOf(items);
    }
}
