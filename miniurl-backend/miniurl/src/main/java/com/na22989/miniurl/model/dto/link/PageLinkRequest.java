package com.na22989.miniurl.model.dto.link;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class PageLinkRequest {

    @Min(value = 1, message = "分页参数不合法")
    private int current = 1;

    @Min(value = 1, message = "分页大小至少为 1")
    @Max(value = 40, message = "分页大小最大为 40")
    private int size = 10;
}
