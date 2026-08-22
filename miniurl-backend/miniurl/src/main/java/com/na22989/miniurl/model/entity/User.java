package com.na22989.miniurl.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;


/**
 * 用户表
 */
@Schema(description="用户表")
@Accessors(chain = true)
@TableName(value = "`user`")
@Data
public class User {
    @TableId(value = "id", type = IdType.AUTO)
    @Schema(description="")
    private Long id;

    /**
     * 用户名
     */
    @TableField(value = "username")
    @Schema(description="用户名")
    private String username;

    /**
     * BCrypt 加密后的密码
     */
    @TableField(value = "`password`")
    @Schema(description="BCrypt 加密后的密码")
    @JsonIgnore
    private String password;

    /**
     * 昵称
     */
    @TableField(value = "nickname")
    @Schema(description="昵称")
    private String nickname;

    @TableField(value = "create_time")
    @Schema(description="")
    private LocalDateTime createTime;

    @TableField(value = "update_time")
    @Schema(description="")
    private LocalDateTime updateTime;


}