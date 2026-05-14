package com.h.backend.user;

import com.h.backend.user.entity.UserEntity;
import com.h.backend.user.entity.UserRoleEntity;
import com.h.backend.user.mapper.UserMapper;
import com.h.backend.user.mapper.UserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class UserMapperPersistenceTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Test
    void shouldInsertUserAndRoleAndQueryByEmail() {
        String email = "tdd_user_" + System.currentTimeMillis() + "@example.com";

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash("hash-value");
        user.setStatus((short) 1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        UserRoleEntity role = new UserRoleEntity();
        role.setUserId(user.getId());
        role.setRoleCode("USER");
        role.setCreatedAt(LocalDateTime.now());
        userRoleMapper.insert(role);

        UserEntity found = userMapper.selectByEmail(email);
        assertNotNull(found);
    }
}
