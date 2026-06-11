package com.ncbaloop.rdas.model;

import java.util.List;

/**
 * Generic paginated response envelope.
 *
 * @param content       the page content
 * @param pageNumber    zero-based current page index
 * @param pageSize      number of items per page
 * @param totalElements total items matching the criteria
 * @param totalPages    total number of pages
 * @param last          whether this is the last page
 */
public record PagedResponse<T>(
        List<T> content,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages,
        boolean last
) {
    public static <T> PagedResponse<T> of(List<T> all, int page, int size) {
        int total = all.size();
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) total / size);
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<T> slice = all.subList(fromIndex, toIndex);
        return new PagedResponse<>(slice, page, size, total, totalPages, (page + 1) >= totalPages);
    }
}
