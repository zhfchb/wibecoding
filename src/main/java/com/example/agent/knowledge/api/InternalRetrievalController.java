package com.example.agent.knowledge.api;

import com.example.agent.knowledge.rag.VectorMatch;
import com.example.agent.knowledge.service.RagRetrievalService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Fixed internal callback, authenticated independently of browser JWTs. */
@RestController
public class InternalRetrievalController {
    private final RagRetrievalService retrieval;
    private final String token;
    public InternalRetrievalController(RagRetrievalService retrieval, @Value("${ai.service.token:}") String token) {
        this.retrieval = retrieval;
        this.token = token;
    }
    public record Query(String query, Long ownerId) {}
    @PostMapping("/internal/v1/retrieve")
    public List<VectorMatch> retrieve(@RequestHeader(value="X-AI-Service-Token", defaultValue="") String supplied,
                                     @RequestBody Query request) {
        if (token.isBlank() || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (request.query() == null || request.query().isBlank() || request.query().length() > 12000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return retrieval.retrieve(request.query(), request.ownerId());
    }
}
