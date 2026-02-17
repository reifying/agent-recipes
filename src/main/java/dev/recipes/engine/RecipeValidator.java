package dev.recipes.engine;

import dev.recipes.model.Recipe;
import dev.recipes.model.Step;
import dev.recipes.model.Transition;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates recipe definitions before execution. See SPEC.md §8.
 */
public final class RecipeValidator {

    private static final Set<String> VALID_MODELS = Set.of("haiku", "sonnet", "opus");

    private RecipeValidator() {}

    /**
     * Validate a recipe definition. Returns an empty list if valid,
     * or a list of error messages if invalid.
     */
    public static List<String> validate(Recipe recipe) {
        var errors = new ArrayList<String>();

        // Rule 1: initialStep must exist in steps
        if (!recipe.steps().containsKey(recipe.initialStep())) {
            errors.add("Initial step not found in steps: " + recipe.initialStep());
        }

        // Rule 6: recipe-level model
        if (recipe.model() != null && !VALID_MODELS.contains(recipe.model())) {
            errors.add("Invalid model '%s' at recipe level. Valid models: %s"
                .formatted(recipe.model(), VALID_MODELS));
        }

        for (var entry : recipe.steps().entrySet()) {
            String stepName = entry.getKey();
            Step step = entry.getValue();

            // Rule 7: required fields
            if (step.prompt() == null || step.prompt().isBlank()) {
                errors.add("Step '%s' has missing or empty prompt".formatted(stepName));
            }
            if (step.outcomes() == null || step.outcomes().isEmpty()) {
                errors.add("Step '%s' has missing or empty outcomes".formatted(stepName));
            }
            if (step.onOutcome() == null) {
                errors.add("Step '%s' has missing onOutcome map".formatted(stepName));
                continue;
            }

            // Rule 2: every onOutcome key must be in outcomes
            for (String outcomeKey : step.onOutcome().keySet()) {
                if (!step.outcomes().contains(outcomeKey)) {
                    errors.add("Step '%s': onOutcome key '%s' not in outcomes %s"
                        .formatted(stepName, outcomeKey, step.outcomes()));
                }
            }

            // Check outcome coverage: every outcome should have onOutcome entry
            for (String outcome : step.outcomes()) {
                if (!step.onOutcome().containsKey(outcome)) {
                    errors.add("Step '%s': outcome '%s' has no onOutcome entry"
                        .formatted(stepName, outcome));
                }
            }

            // Validate each transition
            for (var transEntry : step.onOutcome().entrySet()) {
                String outcomeKey = transEntry.getKey();
                Transition transition = transEntry.getValue();

                switch (transition) {
                    case Transition.NextStep ns -> {
                        // Rule 3: nextStep must reference existing step
                        if (!recipe.steps().containsKey(ns.nextStep())) {
                            errors.add("Step '%s': nextStep '%s' not found in steps"
                                .formatted(stepName, ns.nextStep()));
                        }
                    }
                    case Transition.Exit exit -> {
                        // Rule 4/5: exit must have non-empty reason
                        if (exit.reason() == null || exit.reason().isBlank()) {
                            errors.add("Step '%s': exit transition for outcome '%s' has empty reason"
                                .formatted(stepName, outcomeKey));
                        }
                    }
                    case Transition.RestartNewSession restart -> {
                        if (restart.recipeId() == null || restart.recipeId().isBlank()) {
                            errors.add("Step '%s': restart-new-session transition has empty recipeId"
                                .formatted(stepName));
                        }
                    }
                }
            }

            // Rule 6: step-level model
            if (step.model() != null && !VALID_MODELS.contains(step.model())) {
                errors.add("Invalid model '%s' at step '%s'. Valid models: %s"
                    .formatted(step.model(), stepName, VALID_MODELS));
            }
        }

        return errors;
    }
}
