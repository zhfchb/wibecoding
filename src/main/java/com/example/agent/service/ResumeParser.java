package com.example.agent.service;

import com.fasterxml.jackson.databind.JsonNode;

/** AI extraction boundary; business ownership and persistence stay in Java. */
public interface ResumeParser {
    JsonNode parse(String text);
}
