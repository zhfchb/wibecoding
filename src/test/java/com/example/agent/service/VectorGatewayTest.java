package com.example.agent.service;

import com.example.agent.rag.*;
import com.example.agent.config.RagProperties;
import com.example.agent.controller.InternalRetrievalController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;

class VectorGatewayTest {
    @Test void validatesEmbeddingDimension() throws Exception {
        var gateway = mock(AiGateway.class);
        when(gateway.post(anyString(), any())).thenReturn(new ObjectMapper().readTree("{\"vectors\":[[1,2]]}"));
        assertThat(new PythonEmbeddingModel(gateway, 2).embed("Java")).containsExactly(1f, 2f);
        assertThatThrownBy(() -> new PythonEmbeddingModel(gateway, 3).embed("Java"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dimension");
    }
    @Test void rerankPreservesMetadataAndFallsBackOnInvalidIndex() throws Exception {
        var gateway = mock(AiGateway.class);
        var mapper = new ObjectMapper();
        var matches = List.of(new VectorMatch("a", "doc", "Java", "a.md", 2, .2),
                              new VectorMatch("b", "doc", "Spring", "a.md", 3, .1));
        when(gateway.post(anyString(), any())).thenReturn(mapper.readTree("{\"results\":[{\"index\":1,\"relevance_score\":0.9}]}"));
        assertThat(new PythonRerankModel(gateway).rerank("Spring", matches, 1).get(0).chunkIndex()).isEqualTo(3);
        when(gateway.post(anyString(), any())).thenReturn(mapper.readTree("{\"results\":[{\"index\":9,\"relevance_score\":0.9}]}"));
        assertThat(new PythonRerankModel(gateway).rerank("Spring", matches, 1)).containsExactly(matches.get(0));
    }
    @Test void internalRetrievalRejectsMissingTokenAndOwner() {
        var retrieval = mock(RagRetrievalService.class);
        var controller = new InternalRetrievalController(retrieval, "token");
        assertThatThrownBy(() -> controller.retrieve("wrong", new InternalRetrievalController.Query("Java", 7L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(retrieval);
        var properties = mock(RagProperties.class);
        var service = new RagRetrievalService(mock(EmbeddingModel.class), mock(VectorStore.class), properties,
                new HashingSparseModel(), new NoopRerankModel());
        assertThatThrownBy(() -> service.retrieve("Java", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
