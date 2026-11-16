package com.example.agent.knowledge.service;

import com.example.agent.integration.ai.AiGateway;
import com.example.agent.knowledge.config.RagProperties;
import com.example.agent.knowledge.service.impl.AdaptiveRagServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AdaptiveRagServiceTest {
    @Test void forwardsOwnerAndRetryBound() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("user", "", List.of());
        auth.setDetails(Map.of("userId", 7L));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var gateway = mock(AiGateway.class);
            var properties = mock(RagProperties.class);
            when(properties.adaptive()).thenReturn(new RagProperties.Adaptive(true, 2, .4));
            var mapper = new ObjectMapper();
            when(gateway.post(anyString(), any())).thenReturn(mapper.readTree("{\"answer\":\"answer\",\"sources\":[]}"));
            var result = new AdaptiveRagServiceImpl(gateway, properties, mapper).chat("Java?", "s");
            var body = ArgumentCaptor.forClass(Object.class);
            verify(gateway).post(eq("/internal/v1/rag/adaptive"), body.capture());
            assertThat(((Map<?,?>)body.getValue()).get("maxRetries")).isEqualTo(2);
            assertThat(((Map<?,?>)body.getValue()).get("ownerId")).isEqualTo(7L);
            assertThat(result.sessionId()).isEqualTo("s");
        } finally { SecurityContextHolder.clearContext(); }
    }
}
