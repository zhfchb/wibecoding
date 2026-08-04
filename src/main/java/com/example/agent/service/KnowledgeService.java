package com.example.agent.service;

import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeService {

    KnowledgeUploadResponse upload(MultipartFile file);
}
