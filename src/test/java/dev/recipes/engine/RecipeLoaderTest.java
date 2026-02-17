package dev.recipes.engine;

import dev.recipes.model.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeLoaderTest {

    @Test
    void loadsRecipeFromJsonString() throws IOException {
        String json = """
            {
              "id": "review-and-commit",
              "label": "Review & Commit",
              "description": "Review existing changes, fix issues, and commit",
              "initialStep": "code-review",
              "guardrails": {
                "maxStepVisits": 3,
                "maxTotalSteps": 100,
                "exitOnOther": true
              },
              "steps": {
                "code-review": {
                  "prompt": "Review the code.",
                  "outcomes": ["no-issues", "issues-found", "other"],
                  "onOutcome": {
                    "no-issues": { "nextStep": "commit" },
                    "issues-found": { "nextStep": "fix" },
                    "other": { "action": "exit", "reason": "user-provided-other" }
                  }
                },
                "fix": {
                  "prompt": "Fix the issues.",
                  "outcomes": ["complete", "other"],
                  "onOutcome": {
                    "complete": { "nextStep": "code-review" },
                    "other": { "action": "exit", "reason": "user-provided-other" }
                  }
                },
                "commit": {
                  "prompt": "Commit and push.",
                  "model": "haiku",
                  "outcomes": ["committed", "nothing-to-commit", "other"],
                  "onOutcome": {
                    "committed": { "action": "exit", "reason": "changes-committed" },
                    "nothing-to-commit": { "action": "exit", "reason": "no-changes-to-commit" },
                    "other": { "action": "exit", "reason": "user-provided-other" }
                  }
                }
              }
            }
            """;

        Recipe recipe = RecipeLoader.loadFromString(json);

        assertThat(recipe.id()).isEqualTo("review-and-commit");
        assertThat(recipe.label()).isEqualTo("Review & Commit");
        assertThat(recipe.initialStep()).isEqualTo("code-review");
        assertThat(recipe.steps()).hasSize(3);
        assertThat(recipe.guardrails().maxStepVisits()).isEqualTo(3);
        assertThat(recipe.guardrails().maxTotalSteps()).isEqualTo(100);
        assertThat(recipe.model()).isNull();

        // Verify step details
        Step codeReview = recipe.steps().get("code-review");
        assertThat(codeReview.outcomes()).containsExactlyInAnyOrder("no-issues", "issues-found", "other");
        assertThat(codeReview.model()).isNull();

        Step commit = recipe.steps().get("commit");
        assertThat(commit.model()).isEqualTo("haiku");

        // Verify transitions
        assertThat(codeReview.onOutcome().get("no-issues"))
            .isInstanceOf(Transition.NextStep.class);
        assertThat(((Transition.NextStep) codeReview.onOutcome().get("no-issues")).nextStep())
            .isEqualTo("commit");

        assertThat(commit.onOutcome().get("committed"))
            .isInstanceOf(Transition.Exit.class);
        assertThat(((Transition.Exit) commit.onOutcome().get("committed")).reason())
            .isEqualTo("changes-committed");
    }

    @Test
    void loadsRecipeWithRestartNewSession() throws IOException {
        String json = """
            {
              "id": "implement-and-review-all",
              "label": "Implement & Review All",
              "description": "Implement all tasks with fresh sessions",
              "initialStep": "implement",
              "guardrails": { "maxStepVisits": 3, "maxTotalSteps": 100 },
              "steps": {
                "implement": {
                  "prompt": "Implement the task.",
                  "outcomes": ["complete", "no-tasks", "other"],
                  "onOutcome": {
                    "complete": { "nextStep": "commit" },
                    "no-tasks": { "action": "exit", "reason": "no-tasks" },
                    "other": { "action": "exit", "reason": "user-provided-other" }
                  }
                },
                "commit": {
                  "prompt": "Commit.",
                  "model": "haiku",
                  "outcomes": ["committed", "other"],
                  "onOutcome": {
                    "committed": { "action": "restart-new-session", "recipeId": "implement-and-review-all" },
                    "other": { "action": "exit", "reason": "user-provided-other" }
                  }
                }
              }
            }
            """;

        Recipe recipe = RecipeLoader.loadFromString(json);

        Transition commitTransition = recipe.steps().get("commit").onOutcome().get("committed");
        assertThat(commitTransition).isInstanceOf(Transition.RestartNewSession.class);
        assertThat(((Transition.RestartNewSession) commitTransition).recipeId())
            .isEqualTo("implement-and-review-all");
    }

    @Test
    void defaultsGuardrailsWhenMissing() throws IOException {
        String json = """
            {
              "id": "simple",
              "label": "Simple",
              "description": "Simple recipe",
              "initialStep": "step",
              "steps": {
                "step": {
                  "prompt": "Do thing.",
                  "outcomes": ["done"],
                  "onOutcome": { "done": { "action": "exit", "reason": "completed" } }
                }
              }
            }
            """;

        Recipe recipe = RecipeLoader.loadFromString(json);

        assertThat(recipe.guardrails().maxStepVisits()).isEqualTo(Guardrails.DEFAULT_MAX_STEP_VISITS);
        assertThat(recipe.guardrails().maxTotalSteps()).isEqualTo(Guardrails.DEFAULT_MAX_TOTAL_STEPS);
        assertThat(recipe.guardrails().exitOnOther()).isEqualTo(Guardrails.DEFAULT_EXIT_ON_OTHER);
    }
}
