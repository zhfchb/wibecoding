package com.example.agent.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.entity.DocumentEntity;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeService {

    KnowledgeUploadResponse upload(MultipartFile file);

    /**
     * 分页查询已上传文档元数据。
     */
    IPage<DocumentEntity> listDocuments(int page, int size);
}
