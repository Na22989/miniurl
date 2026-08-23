package com.na22989.miniurl.model.dto.link;

import com.na22989.miniurl.annotation.HttpUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateLinkRequest {
    @NotBlank
    @Size(max = 2048, message = "URL长度不得超过2048字符")
    @HttpUrl
    private String longUrl;

    /**
     * NULL 为永不过期
     */
    private LocalDateTime expireTime;
}
