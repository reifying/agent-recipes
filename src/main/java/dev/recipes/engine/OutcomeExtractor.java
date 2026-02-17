package dev.recipes.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recipes.model.OutcomeResult;

import java.util.Set;

/**
 * Extracts JSON outcome blocks from agent response text.
 * See SPEC.md §2.3 for the extraction algorithm.
 */
public final class OutcomeExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OutcomeExtractor() {}

    /**
     * Extract and validate an outcome from the agent's response text.
     *
     * @param responseText the full agent response
     * @param validOutcomes the set of valid outcome values for the current step
     * @return success with outcome and optional description, or failure with error details
     */
    public static OutcomeResult extract(String responseText, Set<String> validOutcomes) {
        // Step 1: Scan the tail — check last 5 lines for JSON candidate
        String[] lines = responseText.split("\n");
        String candidate = null;
        int startIndex = Math.max(0, lines.length - 5);

        for (int i = lines.length - 1; i >= startIndex; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                candidate = trimmed;
                break;
            }
        }

        if (candidate == null) {
            return new OutcomeResult.Failure("No JSON block found in response", null);
        }

        // Step 2: Strip markdown fences if present
        candidate = stripFences(candidate);

        // Step 3: Parse JSON
        JsonNode node;
        try {
            node = MAPPER.readTree(candidate);
        } catch (Exception e) {
            return new OutcomeResult.Failure("Invalid JSON: " + e.getMessage(), candidate);
        }

        // Step 4: Validate outcome field
        JsonNode outcomeNode = node.get("outcome");
        if (outcomeNode == null || !outcomeNode.isTextual()) {
            return new OutcomeResult.Failure("Missing or non-string 'outcome' field", candidate);
        }

        String outcome = outcomeNode.asText();
        if (!validOutcomes.contains(outcome)) {
            return new OutcomeResult.Failure(
                "Outcome '%s' not in valid outcomes: %s".formatted(outcome, validOutcomes),
                candidate
            );
        }

        // Step 5: Validate otherDescription
        String description = null;
        if ("other".equals(outcome)) {
            JsonNode descNode = node.get("otherDescription");
            if (descNode == null || !descNode.isTextual() || descNode.asText().isBlank()) {
                return new OutcomeResult.Failure(
                    "Outcome 'other' requires non-empty 'otherDescription' field",
                    candidate
                );
            }
            description = descNode.asText();
        }

        return new OutcomeResult.Success(outcome, description);
    }

    private static String stripFences(String candidate) {
        // Remove ```json prefix and ``` suffix if wrapping a single-line JSON
        String stripped = candidate;
        if (stripped.startsWith("```json")) {
            stripped = stripped.substring(7);
        } else if (stripped.startsWith("```")) {
            stripped = stripped.substring(3);
        }
        if (stripped.endsWith("```")) {
            stripped = stripped.substring(0, stripped.length() - 3);
        }
        return stripped.trim();
    }
}
