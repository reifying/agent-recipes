package dev.recipes.model;

import java.util.Map;

/**
 * A recipe is a finite state machine that guides an agent through a series of steps.
 */
public record Recipe(
    String id,
    String label,
    String description,
    String initialStep,
    Map<String, Step> steps,
    Guardrails guardrails,
    String model // nullable — default model tier for all steps
) {}
