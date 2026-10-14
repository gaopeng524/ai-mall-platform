package com.aimall.user.service;

import cn.hutool.crypto.digest.BCrypt;
import com.aimall.common.exception.BusinessException;
import com.aimall.common.exception.ErrorCode;
import com.aimall.common.security.JwtUtil;
import com.aimall.user.dto.UserDTO;
import com.aimall.user.entity.User;
import com.aimall.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;

    /**
     * 注册
     */
    public void register(UserDTO.RegisterRequest req) {
        // 用户名唯一校验
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (count > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(req.getUsername());
        // BCrypt 加密存储
        user.setPassword(BCrypt.hashpw(req.getPassword()));
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setRole("ROLE_USER");
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.insert(user);
        log.info("用户注册成功: {}", req.getUsername());
    }

    /**
     * 登录
     */
    public UserDTO.LoginResponse login(UserDTO.LoginRequest req) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        if (!BCrypt.checkpw(req.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        UserDTO.LoginResponse resp = new UserDTO.LoginResponse();
        resp.setUserId(user.getId());
        resp.setUsername(user.getUsername());
        resp.setRole(user.getRole());
        resp.setToken(token);
        return resp;
    }

    /**
     * 获取用户信息
     */
    public UserDTO.UserInfo getUserInfo(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        UserDTO.UserInfo info = new UserDTO.UserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setEmail(user.getEmail());
        info.setPhone(user.getPhone());
        info.setRole(user.getRole());
        return info;
    }
}
