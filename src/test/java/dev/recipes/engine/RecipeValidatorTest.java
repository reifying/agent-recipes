package dev.recipes.engine;

import dev.recipes.model.*;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeValidatorTest {

    @Test
    void validRecipeReturnsNoErrors() {
        Recipe recipe = new Recipe(
            "test-recipe", "Test Recipe", "A test recipe", "step-a",
            Map.of(
                "step-a", new Step("Do something", Set.of("done", "other"),
                    Map.of(
                        "done", new Transition.Exit("completed"),
                        "other", new Transition.Exit("user-provided-other")
                    ), null)
            ),
            Guardrails.defaults(), null
        );

        assertThat(RecipeValidator.validate(recipe)).isEmpty();
    }

    @Test
    void detectsMissingInitialStep() {
        Recipe recipe = new Recipe(
            "test", "Test", "Test", "nonexistent",
            Map.of(
                "step-a", new Step("Do something", Set.of("done"),
                    Map.of("done", new Transition.Exit("completed")), null)
            ),
            Guardrails.defaults(), null
        );

        var errors = RecipeValidator.validate(recipe);
        assertThat(errors).anyMatch(e -> e.contains("Initial step not found"));
    }

    @Test
    void detectsInvalidNextStep() {
        Recipe recipe = new Recipe(
            "test", "Test", "Test", "step-a",
            Map.of(
                "step-a", new Step("Do something", Set.of("done"),
                    Map.of("done", new Transition.NextStep("nonexistent")), null)
            ),
            Guardrails.defaults(), null
        );

        var errors = RecipeValidator.validate(recipe);
        assertThat(errors).anyMatch(e -> e.contains("nextStep 'nonexistent' not found"));
    }

    @Test
    void detectsInvalidModel() {
        Recipe recipe = new Recipe(
            "test", "Test", "Test", "step-a",
            Map.of(
                "step-a", new Step("Do something", Set.of("done"),
                    Map.of("done", new Transition.Exit("completed")), "gpt-4")
            ),
            Guardrails.defaults(), null
        );

        var errors = RecipeValidator.validate(recipe);
        assertThat(errors).anyMatch(e -> e.contains("Invalid model 'gpt-4'"));
    }

    @Test
    void detectsOutcomeNotCoveredByOnOutcome() {
        Recipe recipe = new Recipe(
            "test", "Test", "Test", "step-a",
            Map.of(
                "step-a", new Step("Do something", Set.of("done", "other"),
                    Map.of("done", new Transition.Exit("completed")), null)
            ),
            Guardrails.defaults(), null
        );

        var errors = RecipeValidator.validate(recipe);
        assertThat(errors).anyMatch(e -> e.contains("outcome 'other' has no onOutcome entry"));
    }

    @Test
    void detectsExitWithEmptyReason() {
        Recipe recipe = new Recipe(
            "test", "Test", "Test", "step-a",
            Map.of(
                "step-a", new Step("Do something", Set.of("done"),
                    Map.of("done", new Transition.Exit("")), null)
            ),
            Guardrails.defaults(), null
        );

        var errors = RecipeValidator.validate(recipe);
        assertThat(errors).anyMatch(e -> e.contains("empty reason"));
    }

    @Test
    void detectsInvalidRecipeLevelModel() {
        Recipe recipe = new Recipe(
            "test", "Test", "Test", "step-a",
            Map.of(
                "step-a", new Step("Do something", Set.of("done"),
                    Map.of("done", new Transition.Exit("completed")), null)
            ),
            Guardrails.defaults(), "gpt-4"
        );

        var errors = RecipeValidator.validate(recipe);
        assertThat(errors).anyMatch(e -> e.contains("Invalid model 'gpt-4' at recipe level"));
    }

    @Test
    void multiStepRecipeWithValidTransitions() {
        Recipe recipe = new Recipe(
            "test", "Test", "Test", "review",
            Map.of(
                "review", new Step("Review code", Set.of("no-issues", "issues-found", "other"),
                    Map.of(
                        "no-issues", new Transition.NextStep("commit"),
                        "issues-found", new Transition.NextStep("fix"),
                        "other", new Transition.Exit("user-provided-other")
                    ), null),
                "fix", new Step("Fix issues", Set.of("complete", "other"),
                    Map.of(
                        "complete", new Transition.NextStep("review"),
                        "other", new Transition.Exit("user-provided-other")
                    ), null),
                "commit", new Step("Commit", Set.of("committed", "other"),
                    Map.of(
                        "committed", new Transition.Exit("changes-committed"),
                        "other", new Transition.Exit("user-provided-other")
                    ), "haiku")
            ),
            Guardrails.defaults(), null
        );

        assertThat(RecipeValidator.validate(recipe)).isEmpty();
    }
}
