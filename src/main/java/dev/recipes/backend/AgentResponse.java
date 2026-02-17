package dev.recipes.backend;

/**
 * Structured response from an agent backend invocation.
 */
public record AgentResponse(
    boolean success,
    String responseText,
    String error,
    String sessionId,
    Long inputTokens,
    Long outputTokens,
    Double costUsd
) {}
