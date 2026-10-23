package org.example.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 登录会话：token → openid 映射，持久化到数据库，服务重启不失效
 */
@TableName("user_session")
public class UserSession {

    /** 登录令牌（UUID 去横线，32 位），主键 */
    @TableId
    private String token;
    private String openid;
    /** 过期时间戳（毫秒） */
    private Long expireAt;
    private LocalDateTime createTime;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getOpenid() { return openid; }
    public void setOpenid(String openid) { this.openid = openid; }
    public Long getExpireAt() { return expireAt; }
    public void setExpireAt(Long expireAt) { this.expireAt = expireAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
