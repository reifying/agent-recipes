package dev.recipes.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recipes.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Loads recipe definitions from JSON files. See SPEC.md §10.
 */
public final class RecipeLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RecipeLoader() {}

    /**
     * Load a single recipe from a JSON file.
     */
    public static Recipe loadFromFile(Path path) throws IOException {
        JsonNode root = MAPPER.readTree(path.toFile());
        return parseRecipe(root);
    }

    /**
     * Load a single recipe from a JSON string.
     */
    public static Recipe loadFromString(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        return parseRecipe(root);
    }

    /**
     * Load all recipes from a directory of JSON files.
     */
    public static Map<String, Recipe> loadFromDirectory(Path dir) throws IOException {
        var recipes = new LinkedHashMap<String, Recipe>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         Recipe recipe = loadFromFile(p);
                         recipes.put(recipe.id(), recipe);
                     } catch (IOException e) {
                         throw new RuntimeException("Failed to load recipe from " + p, e);
                     }
                 });
        }
        return recipes;
    }

    private static Recipe parseRecipe(JsonNode root) {
        String id = root.get("id").asText();
        String label = root.get("label").asText();
        String description = root.get("description").asText();
        String initialStep = root.get("initialStep").asText();
        String model = root.has("model") ? root.get("model").asText() : null;

        Guardrails guardrails = parseGuardrails(root.get("guardrails"));
        Map<String, Step> steps = parseSteps(root.get("steps"));

        return new Recipe(id, label, description, initialStep, steps, guardrails, model);
    }

    private static Guardrails parseGuardrails(JsonNode node) {
        if (node == null) {
            return Guardrails.defaults();
        }
        int maxStepVisits = node.has("maxStepVisits")
            ? node.get("maxStepVisits").asInt() : Guardrails.DEFAULT_MAX_STEP_VISITS;
        int maxTotalSteps = node.has("maxTotalSteps")
            ? node.get("maxTotalSteps").asInt() : Guardrails.DEFAULT_MAX_TOTAL_STEPS;
        boolean exitOnOther = node.has("exitOnOther")
            ? node.get("exitOnOther").asBoolean() : Guardrails.DEFAULT_EXIT_ON_OTHER;
        return new Guardrails(maxStepVisits, maxTotalSteps, exitOnOther);
    }

    private static Map<String, Step> parseSteps(JsonNode stepsNode) {
        var steps = new LinkedHashMap<String, Step>();
        for (var entry : stepsNode.properties()) {
            steps.put(entry.getKey(), parseStep(entry.getValue()));
        }
        return steps;
    }

    private static Step parseStep(JsonNode node) {
        String prompt = node.get("prompt").asText();
        String model = node.has("model") ? node.get("model").asText() : null;

        Set<String> outcomes = new LinkedHashSet<>();
        node.get("outcomes").forEach(o -> outcomes.add(o.asText()));

        Map<String, Transition> onOutcome = new LinkedHashMap<>();
        for (var entry : node.get("onOutcome").properties()) {
            onOutcome.put(entry.getKey(), parseTransition(entry.getValue()));
        }

        return new Step(prompt, outcomes, onOutcome, model);
    }

    private static Transition parseTransition(JsonNode node) {
        if (node.has("nextStep")) {
            return new Transition.NextStep(node.get("nextStep").asText());
        } else if (node.has("action")) {
            String action = node.get("action").asText();
            if ("exit".equals(action)) {
                return new Transition.Exit(node.get("reason").asText());
            } else if ("restart-new-session".equals(action)) {
                return new Transition.RestartNewSession(node.get("recipeId").asText());
            }
        }
        throw new IllegalArgumentException("Unknown transition format: " + node);
    }
}
