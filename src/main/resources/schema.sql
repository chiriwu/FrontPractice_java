-- 微信用户表：openid 唯一，首次 /user/report 时插入
CREATE TABLE IF NOT EXISTS `wx_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `openid` VARCHAR(64) NOT NULL COMMENT '微信用户唯一标识',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='微信用户表';

-- 用户收藏表：(user_id, question_id) 唯一，add 插入/更新，remove 删除，均为最终状态语义
CREATE TABLE IF NOT EXISTS `user_favorite` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT 'wx_user.id',
    `question_id` VARCHAR(64) NOT NULL COMMENT '题目ID',
    `ts` BIGINT NOT NULL COMMENT '客户端收藏时间戳（毫秒），用于排序',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_question` (`user_id`, `question_id`),
    KEY `idx_user_ts` (`user_id`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户收藏表';

-- 登录会话表：token → openid 映射，/user/report 签发，7 天过期，重启不失效
CREATE TABLE IF NOT EXISTS `user_session` (
    `token` VARCHAR(64) NOT NULL COMMENT '登录令牌（UUID 去横线）',
    `openid` VARCHAR(64) NOT NULL COMMENT '微信用户唯一标识',
    `expire_at` BIGINT NOT NULL COMMENT '过期时间戳（毫秒）',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`token`),
    KEY `idx_expire_at` (`expire_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录会话表';
