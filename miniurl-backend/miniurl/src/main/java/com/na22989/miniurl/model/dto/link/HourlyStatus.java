package com.na22989.miniurl.model.dto.link;

import lombok.Data;

/**
 * 单小时统计（近 24h 趋势的一个点）
 * <p>
 * @Data 必须：getter 供 Jackson 序列化（否则接口返回空对象 {}），
 * setter 供 MyBatis resultType 自动映射列到属性。
 */
@Data
public class HourlyStatus {

    private String hour;

    private Long pv;

    private Long uv;

}
