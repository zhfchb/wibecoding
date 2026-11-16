package com.example.agent.service;

import com.example.agent.entity.ChatHistoryEntity;
import com.example.agent.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ChatSessionManager {
    private final JdbcTemplate jdbc;
    private long owner() {
        var id = SecurityUtils.getCurrentUserId();
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return id;
    }
    public List<ChatHistoryEntity> decorate(List<ChatHistoryEntity> sessions) {
        long user = owner();
        var questions = new HashMap<String, String>();
        jdbc.queryForList("""
                SELECT h.session_id,h.content FROM chat_history h JOIN (
                  SELECT session_id,MIN(id) AS first_id FROM chat_history
                  WHERE user_id=? AND role='user' GROUP BY session_id
                ) first_message ON h.id=first_message.first_id
                """, user).forEach(row -> questions.put((String)row.get("session_id"), (String)row.get("content")));
        var metadata = new HashMap<String, Map<String,Object>>();
        jdbc.queryForList("SELECT session_id,title,pinned FROM chat_session WHERE user_id=?", user)
                .forEach(row -> metadata.put((String)row.get("session_id"), row));
        for (var session : sessions) {
            var row = metadata.get(session.getSessionId());
            String customTitle = row == null ? null : (String)row.get("title");
            session.setTitle(customTitle == null || customTitle.isBlank() ? defaultTitle(questions.get(session.getSessionId())) : customTitle);
            session.setPinned(row != null && ((Number)row.get("pinned")).intValue() == 1);
        }
        // Stable sort retains latest-message ordering within each group.
        sessions = new ArrayList<>(sessions);
        sessions.sort(Comparator.comparing(ChatHistoryEntity::isPinned).reversed());
        return sessions;
    }
    static String defaultTitle(String question) {
        if (question == null) return "新会话";
        String title = question.replaceAll("(?m)^\\s{0,3}(?:#{1,6}\\s+|>\\s*)", "")
                .replaceAll("[*`~]", "").replaceAll("[\\s\\p{Z}]+", " ").strip();
        if (title.isEmpty()) return "新会话";
        // Java/JS both validate UTF-16 length; keep the ellipsis within 24 units.
        if (title.length() <= 24) return title;
        int end = 23;
        if (Character.isHighSurrogate(title.charAt(end - 1))) end--;
        return title.substring(0, end) + "…";
    }
    @Transactional
    public void update(String session, String title, Boolean pinned) {
        long user = owner();
        if (title == null && pinned == null) throw new IllegalArgumentException("未提供修改内容");
        if (title != null && (title.isBlank() || title.strip().length() > 80))
            throw new IllegalArgumentException("会话名称须为1到80个字符");
        // Lock history before metadata creation/update; unknown and other users' IDs both return 404.
        var ids = jdbc.queryForList("SELECT id FROM chat_history WHERE user_id=? AND session_id=? FOR UPDATE", Long.class, user, session);
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM chat_session WHERE user_id=? AND session_id=?", Integer.class, user, session) == 0)
            jdbc.update("INSERT INTO chat_session(user_id,session_id,pinned) VALUES(?,?,0)", user, session);
        if (title != null) jdbc.update("UPDATE chat_session SET title=? WHERE user_id=? AND session_id=?", title.strip(), user, session);
        if (pinned != null) jdbc.update("UPDATE chat_session SET pinned=? WHERE user_id=? AND session_id=?", pinned ? 1 : 0, user, session);
    }
    public void delete(String session) {
        jdbc.update("DELETE FROM chat_session WHERE user_id=? AND session_id=?", owner(), session);
    }
}
