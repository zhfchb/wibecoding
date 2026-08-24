package com.example.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.agent.entity.ChatHistoryEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatHistoryMapper extends BaseMapper<ChatHistoryEntity> {

    /**
     * 查询指定用户每个会话的最后一条消息（按 sessionId 分组，取 MAX(id) 那条）。
     * 用于会话列表展示，按最后更新时间倒序。按 user_id 隔离，用户只能看到自己的会话。
     */
    @Select("SELECT * FROM chat_history WHERE user_id = #{userId} AND id IN " +
            "(SELECT MAX(id) FROM chat_history WHERE user_id = #{userId} GROUP BY session_id) " +
            "ORDER BY id DESC")
    List<ChatHistoryEntity> selectLatestOfEachSession(@Param("userId") Long userId);

    /**
     * 删除指定会话的所有历史消息（仅限该用户自己的会话）。
     */
    @Delete("DELETE FROM chat_history WHERE session_id = #{sessionId} AND user_id = #{userId}")
    int deleteBySessionId(@Param("sessionId") String sessionId, @Param("userId") Long userId);
}
