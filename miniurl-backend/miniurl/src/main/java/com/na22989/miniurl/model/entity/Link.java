package com.na22989.miniurl.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 短链表
 */
@Data
@Schema(description="短链表")
@Accessors(chain = true)
@TableName(value = "link")
public class Link {
    @TableId(value = "id", type = IdType.INPUT)
    @Schema(description="")
    private Long id;

    /**
     * 短码（唯一）
     */
    @TableField(value = "short_code")
    @Schema(description="短码（唯一）")
    private String shortCode;

    /**
     * 原始长链接
     */
    @TableField(value = "long_url")
    @Schema(description="原始长链接")
    private String longUrl;

    /**
     * 创建者 ID
     */
    @TableField(value = "user_id")
    @Schema(description="创建者 ID")
    private Long userId;

    /**
     * 过期时间，NULL 为永不过期
     */
    @TableField(value = "expire_time")
    @Schema(description="过期时间，NULL 为永不过期")
    private LocalDateTime expireTime;

    /**
     * 累积点击数
     */
    @TableField(value = "click_count")
    @Schema(description="累积点击数")
    private Integer clickCount;

    @TableField(value = "create_time")
    @Schema(description="")
    private LocalDateTime createTime;

    @TableField(value = "update_time")
    @Schema(description="")
    private LocalDateTime updateTime;

}
