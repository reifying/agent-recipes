package dev.recipes.engine;

import dev.recipes.model.Step;
import dev.recipes.model.Transition;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    @Test
    void buildStepPromptConcatenatesPromptAndOutcomeBlock() {
        Step step = new Step("Review the code.",
            Set.of("no-issues", "issues-found", "other"),
            Map.of(
                "no-issues", new Transition.NextStep("commit"),
                "issues-found", new Transition.NextStep("fix"),
                "other", new Transition.Exit("user-provided-other")
            ), null);

        String prompt = PromptBuilder.buildStepPrompt(step);

        assertThat(prompt).startsWith("Review the code.");
        assertThat(prompt).contains("End your response with one of these JSON blocks");
        assertThat(prompt).contains("{\"outcome\": \"issues-found\"}");
        assertThat(prompt).contains("{\"outcome\": \"no-issues\"}");
        assertThat(prompt).contains("{\"outcome\": \"other\", \"otherDescription\":");
    }

    @Test
    void outcomesAreSortedAlphabeticallyWithOtherLast() {
        Step step = new Step("Do work.",
            Set.of("zebra", "alpha", "middle", "other"),
            Map.of(
                "zebra", new Transition.Exit("done"),
                "alpha", new Transition.Exit("done"),
                "middle", new Transition.Exit("done"),
                "other", new Transition.Exit("user-provided-other")
            ), null);

        String prompt = PromptBuilder.buildStepPrompt(step);

        int alphaIdx = prompt.indexOf("\"alpha\"");
        int middleIdx = prompt.indexOf("\"middle\"");
        int zebraIdx = prompt.indexOf("\"zebra\"");
        int otherIdx = prompt.indexOf("\"other\"");

        assertThat(alphaIdx).isLessThan(middleIdx);
        assertThat(middleIdx).isLessThan(zebraIdx);
        assertThat(zebraIdx).isLessThan(otherIdx);
    }

    @Test
    void reminderPromptIncludesErrorAndValidResponses() {
        Step step = new Step("Review the code.",
            Set.of("no-issues", "issues-found", "other"),
            Map.of(
                "no-issues", new Transition.NextStep("commit"),
                "issues-found", new Transition.NextStep("fix"),
                "other", new Transition.Exit("user-provided-other")
            ), null);

        String reminder = PromptBuilder.buildReminderPrompt(step, "No JSON block found");

        assertThat(reminder).contains("did not include the required JSON outcome block");
        assertThat(reminder).contains("Error: No JSON block found");
        assertThat(reminder).contains("{\"outcome\": \"no-issues\"}");
        assertThat(reminder).contains("Respond with ONLY the JSON block");
    }
}
