package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.domain.auth.AuthResponse;
import com.example.agent.domain.auth.LoginRequest;
import com.example.agent.domain.auth.RegisterRequest;
import com.example.agent.entity.UserEntity;
import com.example.agent.exception.BusinessException;
import com.example.agent.mapper.UserMapper;
import com.example.agent.security.JwtTokenProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * 认证接口:注册 / 登录 / 查当前用户
 */
@Tag(name = "认证授权", description = "用户注册 / 登录 / 当前用户信息")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final org.springframework.jdbc.core.JdbcTemplate db;

    private AuthResponse response(String token,String type,UserEntity user) {
        var result=new AuthResponse(token,type,user.getId(),user.getUsername(),user.getRole());
        var roles=db.queryForList("SELECT role FROM recruitment_company_member WHERE user_id=?",String.class,user.getId());
        result.setCompanyRole(roles.isEmpty()?null:roles.get(0));return result;
    }

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider tokenProvider;

    /** 注册:默认 USER 角色 */
    @Operation(summary = "用户注册", description = "注册新用户,默认 USER 角色。用户名 3-32 位,密码 6-64 位")
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        // 用户名唯一校验
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, request.getUsername()));
        if (exists != null && exists > 0) {
            throw new BusinessException("用户名已被占用: " + request.getUsername());
        }
        UserEntity user = new UserEntity();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole("USER");
        userMapper.insert(user);
        String token = tokenProvider.generate(user.getId(), user.getUsername(), user.getRole());
        return response(token,"Bearer",user);
    }

    /** 登录:用 AuthenticationManager 校验密码,通过后签发 JWT */
    @Operation(summary = "用户登录", description = "用户名密码校验通过后签发 JWT,后续请求带 Authorization: Bearer <token>")
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, authentication.getName()));
        String token = tokenProvider.generate(user.getId(), user.getUsername(), user.getRole());
        return response(token,"Bearer",user);
    }

    /** 查当前登录用户(从 JWT 解出来的 principal 就是 username) */
    @Operation(summary = "查当前用户", description = "根据 JWT 返回当前登录用户信息")
    @SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
    @GetMapping("/me")
    public AuthResponse me(Principal principal) {
        UserEntity user = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, principal.getName()));
        return response(null,null,user);
    }
}
