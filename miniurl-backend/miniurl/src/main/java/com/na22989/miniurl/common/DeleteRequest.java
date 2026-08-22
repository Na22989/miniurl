package com.na22989.miniurl.common;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class DeleteRequest {

    @Min(value = 1, message = "Id 必须大于等于 1")
    private Long id;
}
