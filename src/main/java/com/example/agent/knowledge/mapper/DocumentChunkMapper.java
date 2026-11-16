package com.example.agent.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.knowledge.entity.DocumentChunkEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {

    /**
     * 按文档 ID 查询所有切片。
     */
    @Select("SELECT * FROM document_chunk WHERE document_id = #{documentId} ORDER BY chunk_index ASC")
    List<DocumentChunkEntity> selectByDocumentId(String documentId);

    /**
     * 按文档 ID 删除所有切片。
     */
    @Delete("DELETE FROM document_chunk WHERE document_id = #{documentId}")
    int deleteByDocumentId(String documentId);
}
