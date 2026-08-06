package com.example.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.entity.ChatHistoryEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatHistoryMapper extends BaseMapper<ChatHistoryEntity> {

    /**
     * 查询每个会话的最后一条消息（按 sessionId 分组，取 MAX(id) 那条）。
     * 用于会话列表展示，按最后更新时间倒序。
     */
    @Select("SELECT * FROM chat_history WHERE id IN " +
            "(SELECT MAX(id) FROM chat_history GROUP BY session_id) " +
            "ORDER BY id DESC")
    List<ChatHistoryEntity> selectLatestOfEachSession();

    /**
     * 删除指定会话的所有历史消息。
     */
    @Delete("DELETE FROM chat_history WHERE session_id = #{sessionId}")
    int deleteBySessionId(String sessionId);
}
