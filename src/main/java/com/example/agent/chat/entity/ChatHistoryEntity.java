package com.example.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 对话历史持久化记录
 */
@Data
@TableName("chat_history")
public class ChatHistoryEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID，用于对话数据隔离 */
    private Long userId;

    /** 会话 ID，同一会话的多轮对话共享 */
    private String sessionId;

    /** user / assistant */
    private String role;

    private String content;

    private String sourcesJson;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String title;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private boolean pinned;

    private LocalDateTime createdAt;
}
