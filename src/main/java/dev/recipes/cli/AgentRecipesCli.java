package dev.recipes.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * CLI entry point for agent-recipes. See SPEC.md §9.
 */
@Command(
    name = "agent-recipes",
    mixinStandardHelpOptions = true,
    description = "Orchestrate multi-step, structured workflows for AI coding agents."
)
public class AgentRecipesCli implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Recipe ID to execute")
    private String recipeId;

    @Option(names = "--backend", defaultValue = "claude-code",
        description = "Agent backend: claude-code, github-copilot, cursor, opencode")
    private String backend;

    @Option(names = "--list", description = "List all available recipes")
    private boolean list;

    @Option(names = "--dry-run", description = "Print recipe structure without executing")
    private boolean dryRun;

    @Option(names = "--verbose", description = "Log orchestration state transitions")
    private boolean verbose;

    @Option(names = "--model", description = "Override default model tier for all steps")
    private String model;

    @Option(names = "--max-steps", description = "Override maxTotalSteps guardrail")
    private Integer maxSteps;

    @Option(names = "--max-visits", description = "Override maxStepVisits guardrail")
    private Integer maxVisits;

    @Option(names = "--working-dir", description = "Working directory for agent (default: current directory)")
    private Path workingDir;

    @Option(names = "--system-prompt", description = "Optional system prompt appended to backend default")
    private String systemPrompt;

    @Option(names = "--max-restarts", description = "Cap restart-new-session cycles (default: unlimited)")
    private Integer maxRestarts;

    @Override
    public Integer call() {
        if (list) {
            System.out.println("Available recipes:");
            System.out.println("  (no recipes loaded yet — recipe loading not implemented)");
            return 0;
        }

        if (recipeId == null) {
            System.err.println("Error: recipe ID required. Use --list to see available recipes.");
            return 1;
        }

        System.out.println("Recipe execution not yet implemented. Recipe: " + recipeId);
        return 0;
    }
}
