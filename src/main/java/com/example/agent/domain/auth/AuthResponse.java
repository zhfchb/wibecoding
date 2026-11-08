package com.example.agent.domain.auth;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private String tokenType;
    private Long userId;
    private String username;
    private String role;
    private String companyRole;

    public AuthResponse(String token,String tokenType,Long userId,String username,String role) {
        this(token,tokenType,userId,username,role,null);
    }
}
