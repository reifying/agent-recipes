package dev.recipes.backend;

import java.nio.file.Path;
import java.util.Map;

/**
 * Abstraction over agent CLI backends (Claude Code, Copilot, Cursor, etc.).
 */
public interface AgentBackend {

    /**
     * Send a prompt to the agent and return a structured response.
     *
     * @param prompt       the full prompt text (includes outcome format block)
     * @param sessionId    session identifier for conversation continuity
     * @param isNewSession true = create new session, false = resume existing
     * @param workingDir   working directory for the agent process
     * @param model        resolved model identifier (backend-specific), or null for default
     * @param envVars      additional environment variables to pass to agent process
     * @return structured response with text, usage, and cost
     */
    AgentResponse sendPrompt(String prompt, String sessionId, boolean isNewSession,
                             Path workingDir, String model, Map<String, String> envVars);

    /** Get backend display name. */
    String getName();

    /**
     * Map abstract model tier to backend-specific model identifier.
     *
     * @param tier one of "haiku", "sonnet", "opus"
     * @return backend-specific model ID, or null for backend default
     */
    String resolveModel(String tier);
}
