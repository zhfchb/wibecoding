package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表实体
 * RBAC:单字段 role 存角色(ADMIN/USER),简历项目够用且能讲清 RBAC 思想
 * 表名 sys_user 避免 user 在 H2/MySQL 中的保留字冲突
 */
@Data
@TableName("sys_user")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 角色:ADMIN / USER */
    private String role;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
