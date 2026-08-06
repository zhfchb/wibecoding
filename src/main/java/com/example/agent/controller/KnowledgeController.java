package com.example.agent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.agent.config.OpenApiConfig;
import com.example.agent.domain.knowledge.KnowledgeUploadResponse;
import com.example.agent.entity.DocumentEntity;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "知识库管理", description = "文档上传 / 文档分页查询 / 文档删除")
@SecurityRequirement(name = OpenApiConfig.SECURITY_SCHEME_NAME)
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @Operation(summary = "上传文档", description = "上传 txt/md/markdown 文档,自动切分、Embedding、存入向量库,并写入元数据表")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeUploadResponse upload(@RequestParam("file") MultipartFile file) {
        return knowledgeService.upload(file);
    }

    /**
     * 分页查询已上传文档元数据。
     */
    @Operation(summary = "分页查询文档", description = "分页返回已上传文档的元数据(文件名、切片数、大小、时间)，按当前登录用户隔离")
    @GetMapping("/documents")
    public IPage<DocumentEntity> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return knowledgeService.listDocuments(page, size, SecurityUtils.getCurrentUserId());
    }

    /**
     * 删除指定文档：逻辑删除元数据 + 物理删除 chunk + 删除内存向量库中的向量。
     */
    @Operation(summary = "删除文档", description = "删除指定 documentId 的文档元数据、chunk 持久化、向量库中的向量，只能删自己的文档")
    @DeleteMapping("/documents/{documentId}")
    public void deleteDocument(@PathVariable String documentId) {
        knowledgeService.deleteDocument(documentId, SecurityUtils.getCurrentUserId());
    }
}
