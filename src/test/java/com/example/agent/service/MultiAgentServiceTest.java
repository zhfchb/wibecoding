package com.example.agent.service;
import com.example.agent.config.RagProperties;
import com.example.agent.service.impl.MultiAgentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MultiAgentServiceTest {
    private final AiGateway gateway = mock(AiGateway.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @BeforeEach void login() {
        var auth = new UsernamePasswordAuthenticationToken("user", "", List.of());
        auth.setDetails(Map.of("userId", 7L));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    @Test void forwardsOwnerAndReviewRevision() throws Exception {
        var task = mapper.readTree("{\"taskId\":\"t\",\"status\":\"AWAITING_REVIEW\",\"rejectCount\":1}");
        when(gateway.post(anyString(), any())).thenReturn(task);
        var service = new MultiAgentServiceImpl(gateway, mapper, mock(RagProperties.class));
        service.reject("t", "feedback");
        var body = ArgumentCaptor.forClass(Object.class);
        verify(gateway).post(eq("/internal/v1/tasks/review"), body.capture());
        var map = (Map<?,?>)body.getValue();
        assertThat(map.get("ownerId")).isEqualTo(7L);
        assertThat(map.get("expectedRejectCount")).isEqualTo(1);
        assertThat(map.get("approved")).isEqualTo(false);
    }
    @Test void unauthenticatedCallerCannotReadTasks() {
        SecurityContextHolder.clearContext();
        var service = new MultiAgentServiceImpl(gateway, mapper, mock(RagProperties.class));
        assertThatThrownBy(service::listPendingTasks).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(gateway);
    }
}
