package dev.recipes.engine;

import dev.recipes.model.Step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the full prompt sent to the agent by concatenating the step prompt
 * with the outcome format block. See SPEC.md §2.2.
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    /**
     * Build the full prompt for a step.
     */
    public static String buildStepPrompt(Step step) {
        var sb = new StringBuilder();
        sb.append(step.prompt());
        sb.append("\n\n");
        sb.append(buildOutcomeBlock(step));
        return sb.toString();
    }

    /**
     * Build the reminder prompt for a retry after outcome extraction failure.
     */
    public static String buildReminderPrompt(Step step, String errorDetails) {
        var sb = new StringBuilder();
        sb.append("Your previous response did not include the required JSON outcome block.\n");
        sb.append("Please respond now with ONLY the JSON outcome on a single line.\n\n");
        sb.append("Error: ").append(errorDetails).append("\n\n");
        sb.append("Valid responses:\n\n");
        sb.append(buildOutcomeExamples(step));
        sb.append("\n\nRespond with ONLY the JSON block, nothing else.");
        return sb.toString();
    }

    private static String buildOutcomeBlock(Step step) {
        var sb = new StringBuilder();
        sb.append("End your response with one of these JSON blocks on the last line:\n\n");
        sb.append(buildOutcomeExamples(step));
        return sb.toString();
    }

    private static String buildOutcomeExamples(Step step) {
        var sb = new StringBuilder();
        List<String> sorted = new ArrayList<>(step.outcomes());
        sorted.remove("other");
        Collections.sort(sorted);

        for (String outcome : sorted) {
            sb.append("{\"outcome\": \"").append(outcome).append("\"}\n");
        }

        if (step.outcomes().contains("other")) {
            sb.append("{\"outcome\": \"other\", \"otherDescription\": \"<brief description>\"}");
        }

        return sb.toString();
    }
}
