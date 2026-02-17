package dev.recipes.model;

import java.util.Map;
import java.util.Set;

/**
 * A step represents a single unit of work for the agent to perform.
 */
public record Step(
    String prompt,
    Set<String> outcomes,
    Map<String, Transition> onOutcome,
    String model // nullable — override model tier for this step only
) {}
