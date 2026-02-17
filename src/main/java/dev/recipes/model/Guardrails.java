package dev.recipes.model;

/**
 * Safety limits to prevent infinite loops or runaway execution.
 */
public record Guardrails(
    int maxStepVisits,
    int maxTotalSteps,
    boolean exitOnOther
) {
    public static final int DEFAULT_MAX_STEP_VISITS = 3;
    public static final int DEFAULT_MAX_TOTAL_STEPS = 100;
    public static final boolean DEFAULT_EXIT_ON_OTHER = true;

    public static Guardrails defaults() {
        return new Guardrails(DEFAULT_MAX_STEP_VISITS, DEFAULT_MAX_TOTAL_STEPS, DEFAULT_EXIT_ON_OTHER);
    }
}
