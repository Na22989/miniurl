package com.na22989.miniurl.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.Getter;

import java.util.Date;

/**
 * 访问日志表
 */
@Data
@Schema(description="访问日志表")
@TableName(value = "link_access_log")
public class LinkAccessLog {
    /**
     *
     * @return id
     */
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description="")
    private Long id;

    /**
     * 短链 ID
     *  获取短链 ID
     *
     * @return link_id - 短链 ID

     */
    @TableField(value = "link_id")
    @Schema(description="短链 ID")
    private Long linkId;

    /**
     * 短码（冗余加速查询）
     *  获取短码（冗余加速查询）
     *
     * @return short_code - 短码（冗余加速查询）

     */
    @TableField(value = "short_code")
    @Schema(description="短码（冗余加速查询）")
    private String shortCode;

    /**
     * 访问者 IP (IPv4/IPv6)
     *  获取访问者 IP (IPv4/IPv6)
     *
     * @return ip - 访问者 IP (IPv4/IPv6)

     */
    @TableField(value = "ip")
    @Schema(description="访问者 IP (IPv4/IPv6)")
    private String ip;

    /**
     *
     * @return user_agent
     */
    @TableField(value = "user_agent")
    @Schema(description="")
    private String userAgent;

    /**
     *
     * @return referer
     */
    @TableField(value = "referer")
    @Schema(description="")
    private String referer;

    /**
     *
     * @return access_time
     */
    @TableField(value = "access_time")
    @Schema(description="")
    private Date accessTime;


}