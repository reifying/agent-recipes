package dev.recipes.model;

/**
 * A transition determines what happens after a step completes with a given outcome.
 * Exactly one of three forms: next-step, exit, or restart-new-session.
 */
public sealed interface Transition {

    /** Advance to another step in the recipe. */
    record NextStep(String nextStep) implements Transition {}

    /** Terminate the recipe with a reason. */
    record Exit(String reason) implements Transition {}

    /** Terminate the current session, start a fresh session running the specified recipe. */
    record RestartNewSession(String recipeId) implements Transition {}
}
