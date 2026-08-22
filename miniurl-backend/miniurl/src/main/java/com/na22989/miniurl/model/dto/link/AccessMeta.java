package com.na22989.miniurl.model.dto.link;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AccessMeta {

    private String ip;

    private String userAgent;

    private String referer;
}
