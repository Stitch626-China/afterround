package com.jobpilot.common.api;

import java.util.List;

/**
 * 统一分页结果，对应契约中的 {@code PageResult<T>}。
 *
 * @param items 当前页数据
 * @param total 总条数
 * @param page  页码，从 1 开始
 * @param size  每页条数
 */
public record PageResult<T>(List<T> items, long total, int page, int size) {

    /** 统一分页参数默认值与上限（契约约定：默认 20，最大 100）。 */
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> PageResult<T> of(List<T> items, long total, int page, int size) {
        return new PageResult<>(items, total, page, size);
    }

    /** 规范化页码，防止越界值进入 SQL。 */
    public static int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    /** 规范化每页条数，超上限按上限处理。 */
    public static int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
