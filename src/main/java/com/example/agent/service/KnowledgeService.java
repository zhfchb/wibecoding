package com.example.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.entity.DocumentEntity;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeService {

    KnowledgeUploadResponse upload(MultipartFile file);

    /**
     * 分页查询已上传文档元数据。
     * @param userId 用户隔离过滤，null 表示不过滤（兼容历史数据和测试）
     */
    IPage<DocumentEntity> listDocuments(int page, int size, Long userId);

    /**
     * 删除指定文档：逻辑删除文档元数据 + 物理删除 chunk 持久化 + 删除内存向量库中的向量。
     * @param documentId 文档 ID
     * @param userId 当前用户 ID，用于权限校验（只能删自己的文档）
     */
    void deleteDocument(String documentId, Long userId);
}
