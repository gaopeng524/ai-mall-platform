package com.aimall.user.controller;

import com.aimall.common.exception.BusinessException;
import com.aimall.common.exception.ErrorCode;
import com.aimall.common.result.Result;
import com.aimall.user.dto.UserDTO;
import com.aimall.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 注册 */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody UserDTO.RegisterRequest req) {
        userService.register(req);
        return Result.success("注册成功", null);
    }

    /** 登录 */
    @PostMapping("/login")
    public Result<UserDTO.LoginResponse> login(@Valid @RequestBody UserDTO.LoginRequest req) {
        return Result.success(userService.login(req));
    }

    /** 获取当前用户信息（需登录，userId 由网关 Header 传入） */
    @GetMapping("/me")
    public Result<UserDTO.UserInfo> me(@RequestHeader("X-User-Id") Long userId) {
        return Result.success(userService.getUserInfo(userId));
    }

    /** 内部接口：根据 ID 查用户（供其他服务 Feign 调用） */
    @GetMapping("/internal/{userId}")
    public Result<UserDTO.UserInfo> getById(@PathVariable Long userId) {
        return Result.success(userService.getUserInfo(userId));
    }
}
