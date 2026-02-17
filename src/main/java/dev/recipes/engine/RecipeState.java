package dev.recipes.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Mutable state for a recipe execution.
 */
public final class RecipeState {
    private final String recipeId;
    private String currentStep;
    private int stepCount;
    private final Map<String, Integer> stepVisitCounts;
    private final Map<String, Integer> stepRetryCounts;
    private final long startTime;
    private boolean sessionCreated;

    public RecipeState(String recipeId, String initialStep) {
        this.recipeId = recipeId;
        this.currentStep = initialStep;
        this.stepCount = 1;
        this.stepVisitCounts = new HashMap<>();
        this.stepRetryCounts = new HashMap<>();
        this.startTime = System.currentTimeMillis();
        this.sessionCreated = false;

        this.stepVisitCounts.put(initialStep, 1);
    }

    public String recipeId() { return recipeId; }
    public String currentStep() { return currentStep; }
    public int stepCount() { return stepCount; }
    public long startTime() { return startTime; }
    public boolean sessionCreated() { return sessionCreated; }

    public void markSessionCreated() { this.sessionCreated = true; }

    public int visitCount(String step) {
        return stepVisitCounts.getOrDefault(step, 0);
    }

    public int retryCount(String step) {
        return stepRetryCounts.getOrDefault(step, 0);
    }

    public void incrementRetry(String step) {
        stepRetryCounts.merge(step, 1, Integer::sum);
    }

    /**
     * Transition to the next step: update currentStep, increment stepCount, record visit.
     */
    public void transitionTo(String nextStep) {
        this.currentStep = nextStep;
        this.stepCount++;
        this.stepVisitCounts.merge(nextStep, 1, Integer::sum);
        // Reset retry count for the new step visit
        this.stepRetryCounts.put(nextStep, 0);
    }

    /**
     * Create fresh state for a restart-new-session transition.
     */
    public static RecipeState fresh(String recipeId, String initialStep) {
        return new RecipeState(recipeId, initialStep);
    }
}
