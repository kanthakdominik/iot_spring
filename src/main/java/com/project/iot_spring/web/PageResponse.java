package com.project.iot_spring.web;

import java.util.List;
import java.util.function.Function;

/**
 * Common shape of paged responses (docs/V2.1-SPEC.md, section 3.0):
 * {@code {content, page, size, totalElements, totalPages}}.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, Paging paging, long totalElements) {
        int totalPages = (int) ((totalElements + paging.size() - 1) / paging.size());
        return new PageResponse<>(content, paging.page(), paging.size(), totalElements, totalPages);
    }

    public <R> PageResponse<R> map(Function<T, R> mapper) {
        return new PageResponse<>(content.stream().map(mapper).toList(), page, size, totalElements, totalPages);
    }

    /** Request parameters {@code page} (from 0) and {@code size} (default 24, max 100). */
    public record Paging(int page, int size) {

        public static final int DEFAULT_SIZE = 24;
        public static final int MAX_SIZE = 100;

        public static Paging of(Integer page, Integer size) {
            int p = page == null ? 0 : page;
            int s = size == null ? DEFAULT_SIZE : size;
            if (p < 0) {
                throw new IllegalArgumentException("Parameter 'page' must be >= 0");
            }
            if (s < 1) {
                throw new IllegalArgumentException("Parameter 'size' must be >= 1");
            }
            return new Paging(p, Math.min(s, MAX_SIZE));
        }

        public long offset() {
            return (long) page * size;
        }
    }
}
