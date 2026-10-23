package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.entity.WxUser;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface WxUserMapper extends BaseMapper<WxUser> {
}
