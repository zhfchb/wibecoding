package com.example.agent.chat.service;

import com.example.agent.chat.entity.ChatHistoryEntity;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class ChatSessionManagerTest {
    JdbcTemplate jdbc;
    ChatSessionManager manager;
    @BeforeEach void setup() {
        // Keep the database alive across independent JDBC connections.
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE chat_history(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,session_id VARCHAR(64),role VARCHAR(16),content TEXT)");
        jdbc.execute("CREATE TABLE chat_session(user_id BIGINT,session_id VARCHAR(64),title VARCHAR(80),pinned INT,PRIMARY KEY(user_id,session_id))");
        jdbc.update("INSERT INTO chat_history(user_id,session_id) VALUES(7,'old'),(7,'new'),(8,'other')");
        var auth = new UsernamePasswordAuthenticationToken("user", "", List.of());
        auth.setDetails(Map.of("userId", 7L));
        SecurityContextHolder.getContext().setAuthentication(auth);
        manager = new ChatSessionManager(jdbc);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    ChatHistoryEntity session(String id) { var row = new ChatHistoryEntity(); row.setSessionId(id); return row; }
    @Test void metadataSurvivesReloadAndNewMessages() {
        manager.update("old", "  Java招聘  ", true);
        jdbc.update("INSERT INTO chat_history(user_id,session_id) VALUES(7,'old')");
        var reloaded = new ChatSessionManager(jdbc).decorate(List.of(session("new"), session("old")));
        assertThat(reloaded).extracting(ChatHistoryEntity::getSessionId).containsExactly("old", "new");
        assertThat(reloaded.get(0).getTitle()).isEqualTo("Java招聘");
        manager.update("old", null, false);
        assertThat(manager.decorate(List.of(session("new"),session("old")))).extracting(ChatHistoryEntity::getSessionId).containsExactly("new","old");
        assertThat(manager.decorate(List.of(session("old"))).get(0).getTitle()).isEqualTo("Java招聘");
    }
    @Test void rejectsOtherOwnersUnknownIdsAndInvalidNames() {
        assertThatThrownBy(() -> manager.update("other", "attack", true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> manager.update("missing", "attack", true)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> manager.update("old", " ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.update("old", "x".repeat(81), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_session", Integer.class)).isZero();
    }
    @Test void derivesStableTitleFromFirstUserQuestionAndPreservesRename() {
        jdbc.update("INSERT INTO chat_history(user_id,session_id,role,content) VALUES(7,'old','user',?),(7,'old','assistant',?),(7,'old','user',?),(8,'old','user',?)",
                "# Java岗位\n **必须技能** 是什么？", "不应当拿这段回答当标题".repeat(10), "第二个问题", "别人的私密问题");
        String expected="Java岗位 必须技能 是什么？";
        assertThat(manager.decorate(List.of(session("old"))).get(0).getTitle()).isEqualTo(expected);
        manager.update("old",null,true);
        assertThat(new ChatSessionManager(jdbc).decorate(List.of(session("old"))).get(0).getTitle()).isEqualTo(expected);
        manager.update("old","我的招聘问题",null);
        jdbc.update("INSERT INTO chat_history(user_id,session_id,role,content) VALUES(7,'old','user','后续问题')");
        assertThat(manager.decorate(List.of(session("old"))).get(0).getTitle()).isEqualTo("我的招聘问题");
        assertThat(manager.decorate(List.of(session("new"))).get(0).getTitle()).isEqualTo("新会话");
    }
    @Test void boundsDefaultTitleIncludingEllipsisAndUnicode() {
        assertThat(ChatSessionManager.defaultTitle("问".repeat(100))).hasSize(24).endsWith("…");
        assertThat(ChatSessionManager.defaultTitle("a".repeat(22)+"😀"+"长问题")).isEqualTo("a".repeat(22)+"…");
        assertThat(ChatSessionManager.defaultTitle(" \n \t")).isEqualTo("新会话");
    }
}
