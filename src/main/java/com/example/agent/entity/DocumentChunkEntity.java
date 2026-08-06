package com.example.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档切片持久化：存 chunk 文本 + 向量 JSON，进程重启后可从 MySQL 恢复内存向量库。
 */
@Data
@TableName("document_chunk")
public class DocumentChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 切片唯一 ID（UUID），对应 ChunkRecord.id */
    private String chunkId;

    /** 所属文档 ID */
    private String documentId;

    private String fileName;

    private Integer chunkIndex;

    /** 切片正文 */
    private String text;

    /** 稠密向量 JSON（float[] 序列化） */
    private String denseVector;

    /** 稀疏向量索引 JSON（int[] 序列化），可为空 */
    private String sparseIndices;

    /** 稀疏向量值 JSON（float[] 序列化），可为空 */
    private String sparseValues;

    /** 所属用户 ID，null 表示全局共享 */
    private Long userId;

    private LocalDateTime createdAt;
}
