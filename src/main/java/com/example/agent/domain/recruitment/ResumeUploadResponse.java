package com.example.agent.domain.recruitment;

import java.util.List;

/**
 * 批量上传简历结果：逐个文件的成功/失败状态
 */
public record ResumeUploadResponse(
        int total,
        int successCount,
        int failedCount,
        List<ResumeUploadItem> items) {

    public record ResumeUploadItem(
            String fileName,
            boolean success,
            Long resumeId,
            String candidateName,
            String error) {
    }
}
