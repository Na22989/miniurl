package com.na22989.miniurl.common;


import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 当前页的数据列表
     */
    private List<T> records;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页码
     */
    private long current;

    /**
     * 每页大小
     */
    private long size;

    /**
     * 总页数
     */
    private long pages;
}
