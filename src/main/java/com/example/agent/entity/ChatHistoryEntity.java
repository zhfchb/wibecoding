package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话历史持久化记录
 */
@Data
@TableName("chat_history")
public class ChatHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话 ID，同一会话的多轮对话共享 */
    private String sessionId;

    /** user / assistant */
    private String role;

    private String content;

    private LocalDateTime createdAt;
}
