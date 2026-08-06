package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * LangGraph4j checkpoint 持久化实体（M9）。
 */
@Data
@TableName("langgraph_checkpoint")
public class LangGraphCheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String threadId;

    private String checkpointId;

    private String nodeId;

    private String nextNodeId;

    private String stateJson;

    private LocalDateTime createdAt;
}
