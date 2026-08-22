CREATE TABLE `user` (
                        `id`          BIGINT       NOT NULL AUTO_INCREMENT,
                        `username`    VARCHAR(50)  NOT NULL COMMENT '用户名',
                        `password`    VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密后的密码',
                        `nickname`    VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
                        `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        PRIMARY KEY (`id`),
                        UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- auto-generated definition
create table link
(
    id          bigint auto_increment
        primary key,
    short_code  varchar(10)                        not null comment '短码（唯一）',
    long_url    varchar(2048)                      not null comment '原始长链接',
    user_id     bigint                             not null comment '创建者 ID',
    expire_time datetime                           null comment '过期时间，NULL 为永不过期',
    click_count int      default 0                 not null comment '累积点击数',
    create_time datetime default CURRENT_TIMESTAMP not null,
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP,
    constraint uk_short_code
        unique (short_code)
)
    comment '短链表';

create index idx_create_time
    on link (create_time);

create index idx_expire_time
    on link (expire_time);

create index idx_user_id
    on link (user_id);

-- ============================================================
-- 访问日志表（Week 4 Day 3）：短链访问明细，供访问统计 + 日志分析
-- ============================================================
CREATE TABLE `link_access_log` (
  `id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `link_id`     BIGINT        NOT NULL COMMENT '短链 ID',
  `short_code`  VARCHAR(10)   NOT NULL COMMENT '短码（冗余加速查询）',
  `ip`          VARCHAR(45)   NOT NULL COMMENT '访问者 IP (IPv4/IPv6)',
  `user_agent`  VARCHAR(500)  DEFAULT NULL COMMENT '浏览器 UA',
  `referer`     VARCHAR(2048) DEFAULT NULL COMMENT '来源页',
  `access_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '访问时间',
  PRIMARY KEY (`id`),
  INDEX `idx_link_id_time` (`link_id`, `access_time`),
  INDEX `idx_short_code_time` (`short_code`, `access_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访问日志表';

