package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.entity.UserSession;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {
}
