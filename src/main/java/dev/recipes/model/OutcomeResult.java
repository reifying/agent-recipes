package dev.recipes.model;

/**
 * Result of extracting an outcome from an agent response.
 */
public sealed interface OutcomeResult {

    record Success(String outcome, String description) implements OutcomeResult {}

    record Failure(String error, String malformedJson) implements OutcomeResult {}
}
