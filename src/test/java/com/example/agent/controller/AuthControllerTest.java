package com.example.agent.controller;

import com.example.agent.entity.UserEntity;
import com.example.agent.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与鉴权集成测试
 * 覆盖:注册 / 登录 / @Valid 校验 / JWT 鉴权 / RBAC 角色控制
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUsers() {
        // H2 内存库,每条测试前清空 user 表,避免 UNIQUE 冲突
        userMapper.delete(null);
    }

    /** 预置一个指定角色的用户,返回明文密码 */
    private void precreateUser(String username, String rawPassword, String role) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole(role);
        userMapper.insert(user);
    }

    /** 从 JSON 结果里提取 token */
    @SuppressWarnings("unchecked")
    private String extractToken(MvcResult result) throws Exception {
        Map<String, Object> body = objectMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    // -------- 注册 --------

    @Test
    void registerReturnsTokenAndDefaultUserRole() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"alice","password":"alice123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void registerRejectsDuplicateUsername() throws Exception {
        precreateUser("bob", "bob123", "USER");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bob","password":"bob456"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsInvalidInput() throws Exception {
        // 密码太短(< 6)
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"123"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // -------- 登录 --------

    @Test
    void loginReturnsTokenForCorrectCredentials() throws Exception {
        precreateUser("carol", "carol123", "USER");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"carol","password":"carol123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.username").value("carol"));
    }

    @Test
    void loginRejectsWrongPassword() throws Exception {
        precreateUser("dave", "dave123", "USER");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"dave","password":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // -------- JWT 鉴权 --------

    @Test
    void meReturns401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsUserWithValidToken() throws Exception {
        precreateUser("eve", "eve123", "USER");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"eve","password":"eve123"}
                                """))
                .andReturn();
        String token = extractToken(loginResult);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("eve"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    // -------- RBAC --------

    @Test
    void adminEndpointReturns403ForUserRole() throws Exception {
        precreateUser("frank", "frank123", "USER");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"frank","password":"frank123"}
                                """))
                .andReturn();
        String token = extractToken(loginResult);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminEndpointReturns200ForAdminRole() throws Exception {
        precreateUser("root", "root123", "ADMIN");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"root","password":"root123"}
                                """))
                .andReturn();
        String token = extractToken(loginResult);

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void adminEndpointReturns401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
    }
}
