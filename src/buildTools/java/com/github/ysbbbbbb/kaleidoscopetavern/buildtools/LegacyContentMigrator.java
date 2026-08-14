package com.github.ysbbbbbb.kaleidoscopetavern.buildtools;

import com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.orchestrator.LegacyMigrationOrchestrator;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Java 25 command-line entry point for the native legacy-content migration. */
public final class LegacyContentMigrator {
    private static final String ITEM_REGISTRY =
            "src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/init/ModItems.java";
    private static final String BLOCK_REGISTRY =
            "src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/init/ModBlocks.java";

    private LegacyContentMigrator() {}

    public static void main(String[] arguments) {
        System.exit(run(arguments, System.out, System.err));
    }

    /** Runs the CLI without terminating the hosting JVM, so build-tool tests can invoke it directly. */
    public static int run(String[] arguments, PrintStream out, PrintStream err) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        final Options options;
        try {
            options = parse(arguments);
        } catch (IllegalArgumentException exception) {
            err.println("LegacyContentMigrator: error: " + exception.getMessage());
            err.println("Try 'LegacyContentMigrator --help' for usage.");
            return 2;
        }
        if (options.help()) {
            printHelp(out);
            return 0;
        }

        try {
            validateInputRoot(options.projectRoot());
            Path outputRoot = options.outputRoot();
            boolean temporaryOutput = options.temporaryOutput();
            if (temporaryOutput) {
                outputRoot = Files.createTempDirectory("kaleidoscope-legacy-migration-");
            } else if (outputRoot == null) {
                // Build-tool mode: no OUTPUT means regenerate the managed files in this project.
                outputRoot = options.projectRoot();
            }

            LegacyMigrationOrchestrator.Result result = new LegacyMigrationOrchestrator(
                    options.projectRoot(), outputRoot, List.of()).run();
            printResult(out, outputRoot, temporaryOutput, result);
            return 0;
        } catch (Exception exception) {
            err.println("LegacyContentMigrator: migration failed: " + exception.getMessage());
            if (options.verbose()) exception.printStackTrace(err);
            return 1;
        }
    }

    private static Options parse(String[] arguments) {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path outputRoot = null;
        boolean verbose = false;
        boolean help = false;
        boolean temporaryOutput = false;
        List<String> positional = new java.util.ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            try {
                switch (argument) {
                    case "-h", "--help" -> help = true;
                    case "-v", "--verbose" -> verbose = true;
                    case "--root" -> projectRoot = absolutePath(requireValue(arguments, ++index, argument));
                    case "--output", "--output-root" ->
                            outputRoot = absolutePath(requireValue(arguments, ++index, argument));
                    case "--temporary-output", "--dry-run" -> temporaryOutput = true;
                    default -> {
                        if (argument.startsWith("-")) {
                            throw new IllegalArgumentException("unrecognized argument: " + argument);
                        }
                        positional.add(argument);
                    }
                }
            } catch (InvalidPathException exception) {
                throw new IllegalArgumentException(
                        "invalid path for " + argument + ": " + exception.getInput(), exception);
            }
        }
        if (positional.size() > 2) {
            throw new IllegalArgumentException("expected at most two positional paths: ROOT [OUTPUT]");
        }
        if (!positional.isEmpty()) {
            if (!projectRoot.equals(Path.of("").toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("ROOT was supplied both positionally and with --root");
            }
            projectRoot = absolutePath(positional.getFirst());
        }
        if (positional.size() == 2) {
            if (outputRoot != null) {
                throw new IllegalArgumentException("OUTPUT was supplied both positionally and with --output");
            }
            outputRoot = absolutePath(positional.get(1));
        }
        if (temporaryOutput && outputRoot != null) {
            throw new IllegalArgumentException("--temporary-output cannot be combined with an explicit OUTPUT");
        }
        return new Options(projectRoot, outputRoot, temporaryOutput, verbose, help);
    }

    private static Path absolutePath(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String requireValue(String[] arguments, int index, String option) {
        if (index >= arguments.length || arguments[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a path");
        }
        return arguments[index];
    }

    private static void validateInputRoot(Path root) {
        if (!Files.isRegularFile(root.resolve(ITEM_REGISTRY))
                || !Files.isRegularFile(root.resolve(BLOCK_REGISTRY))) {
            throw new IllegalArgumentException(
                    "project root does not contain the archived Forge registries: " + root);
        }
    }

    private static void printResult(PrintStream out, Path outputRoot, boolean temporary,
                                    LegacyMigrationOrchestrator.Result result) {
        JsonObject report = new JsonObject();
        report.addProperty("source", "KaleidoscopeTavern Forge 1.20.1 data generators");
        report.addProperty("target", "Paper 26.2 + CraftEngine 26.8");
        result.metrics().forEach(report::addProperty);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        out.println(gson.toJson(report));
        out.println("output=" + outputRoot);
        out.println("recipes.sha256=" + result.recipesSha256());
        if (temporary) out.println("Temporary output is retained for inspection.");
    }

    private static void printHelp(PrintStream out) {
        out.println("usage: LegacyContentMigrator [OPTIONS] [ROOT [OUTPUT]]");
        out.println();
        out.println("Runs the native Java migration without invoking Python.");
        out.println("With no OUTPUT it uses build-tool mode and regenerates managed files under ROOT.");
        out.println();
        out.println("  --root PATH         repository/input root (default: current directory)");
        out.println("  --output PATH       output root; alias: --output-root");
        out.println("  --temporary-output  generate into a retained temporary root; alias: --dry-run");
        out.println("  ROOT [OUTPUT]       positional equivalents of --root and --output");
        out.println("  -v, --verbose       print a stack trace when migration fails");
        out.println("  -h, --help          show this help");
    }

    private record Options(Path projectRoot, Path outputRoot, boolean temporaryOutput,
                           boolean verbose, boolean help) {}
}
