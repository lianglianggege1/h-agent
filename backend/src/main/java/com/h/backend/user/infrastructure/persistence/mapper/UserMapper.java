package com.h.backend.user.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.user.infrastructure.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("SELECT id, email, password_hash, status, created_at, updated_at FROM users WHERE email = #{email} LIMIT 1")
    UserEntity selectByEmail(String email);
}