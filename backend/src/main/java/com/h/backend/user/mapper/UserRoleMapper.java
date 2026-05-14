package com.h.backend.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.h.backend.user.entity.UserRoleEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleEntity> {

    @Select("SELECT id, user_id, role_code, created_at FROM user_roles WHERE user_id = #{userId} LIMIT 1")
    UserRoleEntity selectFirstByUserId(Long userId);
}
