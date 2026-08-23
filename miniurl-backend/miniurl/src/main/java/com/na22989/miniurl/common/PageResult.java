package com.na22989.miniurl.common;


import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;

    private long total;

    private long current;

    private long size;

    private long pages;
}
