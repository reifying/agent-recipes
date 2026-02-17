package dev.recipes.engine;

import dev.recipes.model.OutcomeResult;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeExtractorTest {

    private static final Set<String> REVIEW_OUTCOMES = Set.of("no-issues", "issues-found", "other");

    @Test
    void extractsValidOutcomeFromLastLine() {
        String response = """
            I reviewed the code and found no issues.

            {"outcome": "no-issues"}""";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Success.class);
        var success = (OutcomeResult.Success) result;
        assertThat(success.outcome()).isEqualTo("no-issues");
        assertThat(success.description()).isNull();
    }

    @Test
    void extractsOutcomeFromWithinLast5Lines() {
        String response = """
            I reviewed the code and found issues.

            {"outcome": "issues-found"}

            """;

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Success.class);
        assertThat(((OutcomeResult.Success) result).outcome()).isEqualTo("issues-found");
    }

    @Test
    void extractsOtherWithDescription() {
        String response = """
            Something unexpected happened.

            {"outcome": "other", "otherDescription": "Could not access the repository"}""";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Success.class);
        var success = (OutcomeResult.Success) result;
        assertThat(success.outcome()).isEqualTo("other");
        assertThat(success.description()).isEqualTo("Could not access the repository");
    }

    @Test
    void failsWhenOtherMissingDescription() {
        String response = """
            Something happened.
            {"outcome": "other"}""";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Failure.class);
        assertThat(((OutcomeResult.Failure) result).error()).contains("otherDescription");
    }

    @Test
    void failsWhenNoJsonBlockFound() {
        String response = "I did the review and everything looks good.";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Failure.class);
        assertThat(((OutcomeResult.Failure) result).error()).contains("No JSON block found");
    }

    @Test
    void failsWhenOutcomeNotInValidSet() {
        String response = """
            Done.
            {"outcome": "invalid-outcome"}""";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Failure.class);
        assertThat(((OutcomeResult.Failure) result).error()).contains("not in valid outcomes");
    }

    @Test
    void handlesJsonOnLineWithSurroundingFences() {
        // Fences on surrounding lines — the JSON line itself still starts with { and ends with }
        String response = """
            Review complete.
            ```json
            {"outcome": "no-issues"}
            ```""";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Success.class);
        assertThat(((OutcomeResult.Success) result).outcome()).isEqualTo("no-issues");
    }

    @Test
    void failsOnMalformedJson() {
        String response = """
            Done reviewing.
            {"outcome": "no-issues""";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        // The malformed JSON line doesn't end with }, so no candidate is found
        assertThat(result).isInstanceOf(OutcomeResult.Failure.class);
    }

    @Test
    void ignoresJsonBeyondLast5Lines() {
        String response = """
            {"outcome": "no-issues"}
            line 1
            line 2
            line 3
            line 4
            line 5""";

        var result = OutcomeExtractor.extract(response, REVIEW_OUTCOMES);

        assertThat(result).isInstanceOf(OutcomeResult.Failure.class);
        assertThat(((OutcomeResult.Failure) result).error()).contains("No JSON block found");
    }
}
