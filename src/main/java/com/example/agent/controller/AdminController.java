package com.example.agent.controller;

import com.example.agent.config.OpenApiConfig;
import com.example.agent.entity.UserEntity;
import com.example.agent.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理员接口:需要 ADMIN 角色
 * 用于验证 RBAC 鉴权
 */
@Tag(name = "管理员", description = "管理员接口,需 ADMIN 角色")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserMapper userMapper;

    /** 查所有用户列表(仅 ADMIN) */
    @Operation(summary = "查所有用户", description = "返回所有用户列表,仅 ADMIN 角色可访问")
    @GetMapping("/users")
    public List<UserEntity> listUsers() {
        return userMapper.selectList(null);
    }
}
