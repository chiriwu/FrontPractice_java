package org.example.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.example.entity.UserFavorite;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {
}
