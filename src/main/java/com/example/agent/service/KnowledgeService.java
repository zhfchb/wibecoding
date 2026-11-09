package com.example.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.entity.DocumentEntity;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeService {

    KnowledgeUploadResponse upload(MultipartFile file);

    /** DB-only callback joins atomic metadata/chunk publication, after external model/vector calls. */
    KnowledgeUploadResponse upload(MultipartFile file, java.util.function.Consumer<String> onPublish);

    /**
     * 分页查询已上传文档元数据。
     * @param userId 用户隔离过滤，null 表示不过滤（兼容历史数据和测试）
     */
    IPage<DocumentEntity> listDocuments(int page, int size, Long userId);

    /**
     * 删除指定文档：事务内逻辑删除元数据/切片并登记清理任务，立即禁止检索，后台重试向量物理删除。
     * @param documentId 文档 ID
     * @param userId 当前用户 ID，用于权限校验（只能删自己的文档）
     */
    void deleteDocument(String documentId, Long userId);
}
