package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档元数据
 */
@Data
@TableName("document")
public class DocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务文档 ID（UUID），与向量库中 document_id 对应 */
    private String documentId;

    private String fileName;

    private Integer chunkCount;

    private Long fileSize;

    /** 上传者用户 ID，null 表示全局共享（历史数据兼容） */
    private Long userId;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
