package dev.recipes;

import dev.recipes.cli.AgentRecipesCli;
import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new AgentRecipesCli()).execute(args);
        System.exit(exitCode);
    }
}
