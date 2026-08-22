package com.na22989.miniurl.model.vo.link;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkVO {

    private Long id;

    private String shortCode;

    private String shortUrl;

    private String longUrl;

    private Long userId;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;
}
