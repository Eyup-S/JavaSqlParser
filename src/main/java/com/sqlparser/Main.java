package com.sqlparser;

import com.sqlparser.exporter.SqlExporter;
import com.sqlparser.extractor.QueryExtractor;
import com.sqlparser.importer.SqlImporter;
import com.sqlparser.model.ScanConfig;
import com.sqlparser.parser.ParserModule;
import com.sqlparser.registry.QueryRegistry;
import com.sqlparser.rewriter.CodeRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the Java SQL Parser tool.
 *
 * Usage:
 *   java -jar parser.jar extract  <source-dir> [output-dir] [options]
 *   java -jar parser.jar replace  <source-dir> <converted-sql-file> [registry-json] [options]
 *   java -jar parser.jar report   <source-dir> [output-dir] [options]
 *
 * Options:
 *   --mode=all          Extract every query (default)
 *   --mode=oracle-only  Extract only queries containing Oracle-specific keywords
 *   --exclude=<regex>   Skip Java files whose absolute path matches the regex
 *                       e.g.  --exclude=.*Test.*
 *                             --exclude=.*generated.*|.*legacy.*
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        String command = args[0].toLowerCase();

        try {
            switch (command) {
                case "extract" -> runExtract(args);
                case "replace" -> runReplace(args);
                case "report"  -> runReport(args);
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            log.error("Fatal error: {}", e.getMessage(), e);
            System.exit(2);
        }
    }

    // ── extract ───────────────────────────────────────────────────────────────

    private static void runExtract(String[] args) throws Exception {
        // Positional args: extract <source-dir> [output-dir]
        Path sourceDir = Path.of(args[1]);
        Path outputDir = findPositionalOrDefault(args, 2, "output");

        ScanConfig config = parseScanConfig(args);

        validateDirectory(sourceDir);
        Files.createDirectories(outputDir);

        log.info("=== EXTRACT ===");
        log.info("Source : {}", sourceDir.toAbsolutePath());
        log.info("Output : {}", outputDir.toAbsolutePath());
        log.info("Config : {}", config);

        // 1. Parse (with file exclusion)
        ParserModule parser = new ParserModule(sourceDir);
        List<ParserModule.ParsedFile> parsedFiles = parser.parseDirectory(sourceDir, config);

        // 2. Extract (with scan mode filter)
        QueryRegistry registry = new QueryRegistry();
        QueryExtractor extractor = new QueryExtractor(registry, config);
        extractor.extractAll(parsedFiles);

        if (registry.size() == 0) {
            log.warn("No queries were found in {} (mode: {})", sourceDir, config.getMode());
        }

        // 3. Export SQL
        SqlExporter exporter = new SqlExporter(registry);
        Path sqlOutput = outputDir.resolve("queries.sql");
        exporter.exportToFile(sqlOutput);

        // 4. Save registry JSON
        Path registryJson = outputDir.resolve("registry.json");
        registry.saveToJson(registryJson);

        // 5. Export report
        Path reportFile = outputDir.resolve("report.txt");
        exporter.exportReport(reportFile);

        System.out.println();
        System.out.println("Done.");
        System.out.printf("  Mode           : %s%n", config.getMode());
        System.out.printf("  Queries found  : %d%n", registry.size());
        System.out.printf("  SQL output     : %s%n", sqlOutput.toAbsolutePath());
        System.out.printf("  Registry JSON  : %s%n", registryJson.toAbsolutePath());
        System.out.printf("  Report         : %s%n", reportFile.toAbsolutePath());
        System.out.println();
        System.out.println("Next step:");
        System.out.println("  Run ora2pg on queries.sql — preserve the -- BEGIN_QID / END_QID markers.");
        System.out.printf("  Then: java -jar parser.jar replace %s <converted.sql> %s%n",
                sourceDir, registryJson);
    }

    // ── replace ───────────────────────────────────────────────────────────────

    private static void runReplace(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("replace requires: <source-dir> <converted-sql-file> [registry-json]");
            printUsage();
            System.exit(1);
        }

        Path sourceDir = Path.of(args[1]);
        Path convertedSqlFile = Path.of(args[2]);
        Path registryJson = findPositionalOrDefault(args, 3, "output/registry.json");

        validateDirectory(sourceDir);
        validateFile(convertedSqlFile);
        validateFile(registryJson);

        log.info("=== REPLACE ===");
        log.info("Source    : {}", sourceDir.toAbsolutePath());
        log.info("Converted : {}", convertedSqlFile.toAbsolutePath());
        log.info("Registry  : {}", registryJson.toAbsolutePath());

        // 1. Load registry
        QueryRegistry registry = new QueryRegistry();
        registry.loadFromJson(registryJson);
        log.info("Registry loaded: {} queries", registry.size());

        // 2. Import conversions
        SqlImporter importer = new SqlImporter();
        Map<String, String> conversions = importer.importConversions(convertedSqlFile);
        log.info("Conversions imported: {}", conversions.size());

        if (conversions.isEmpty()) {
            log.warn("No conversions found — ensure BEGIN_QID/END_QID markers are preserved in {}", convertedSqlFile);
            System.exit(0);
        }

        // 3. Apply conversions
        registry.applyConversions(conversions);

        // 4. Rewrite source files
        CodeRewriter rewriter = new CodeRewriter(registry);
        int rewritten = rewriter.rewriteAll(sourceDir);

        // 5. Save updated registry
        registry.saveToJson(registryJson);

        System.out.println();
        System.out.println("Done.");
        System.out.printf("  Queries replaced : %d%n", rewritten);
        System.out.printf("  Registry updated : %s%n", registryJson.toAbsolutePath());
        System.out.println();
        System.out.println("Originals backed up as <file>.java.bak — verify compilation before committing.");
    }

    // ── report ────────────────────────────────────────────────────────────────

    private static void runReport(String[] args) throws Exception {
        Path sourceDir = Path.of(args[1]);
        Path outputDir = findPositionalOrDefault(args, 2, "output");

        ScanConfig config = parseScanConfig(args);

        validateDirectory(sourceDir);
        Files.createDirectories(outputDir);

        log.info("=== REPORT === (mode: {})", config.getMode());

        ParserModule parser = new ParserModule(sourceDir);
        List<ParserModule.ParsedFile> parsedFiles = parser.parseDirectory(sourceDir, config);

        QueryRegistry registry = new QueryRegistry();
        QueryExtractor extractor = new QueryExtractor(registry, config);
        extractor.extractAll(parsedFiles);

        SqlExporter exporter = new SqlExporter(registry);
        Path reportFile = outputDir.resolve("report.txt");
        exporter.exportReport(reportFile);

        System.out.printf("Report written to: %s  (%d queries, mode=%s)%n",
                reportFile.toAbsolutePath(), registry.size(), config.getMode());
    }

    // ── Argument parsing ──────────────────────────────────────────────────────

    /**
     * Parses --mode and --exclude flags from anywhere in the args array.
     */
    private static ScanConfig parseScanConfig(String[] args) {
        ScanConfig.ScanMode mode = ScanConfig.ScanMode.ALL;
        String excludePattern = null;

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                String val = arg.substring("--mode=".length()).trim().toLowerCase();
                mode = switch (val) {
                    case "oracle-only", "oracle_only", "oracleonly" -> ScanConfig.ScanMode.ORACLE_ONLY;
                    case "all" -> ScanConfig.ScanMode.ALL;
                    default -> throw new IllegalArgumentException(
                            "Unknown mode '" + val + "'. Use: --mode=all  or  --mode=oracle-only");
                };
            } else if (arg.startsWith("--exclude=")) {
                excludePattern = arg.substring("--exclude=".length());
            }
        }

        return ScanConfig.of(mode, excludePattern);
    }

    /**
     * Returns a Path from a positional argument, or a default string if absent or starts with "--".
     */
    private static Path findPositionalOrDefault(String[] args, int index, String defaultValue) {
        if (args.length > index && !args[index].startsWith("--")) {
            return Path.of(args[index]);
        }
        return Path.of(defaultValue);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static void validateDirectory(Path path) {
        if (!Files.isDirectory(path)) {
            System.err.println("Not a directory: " + path.toAbsolutePath());
            System.exit(1);
        }
    }

    private static void validateFile(Path path) {
        if (!Files.isRegularFile(path)) {
            System.err.println("File not found: " + path.toAbsolutePath());
            System.exit(1);
        }
    }

    // ── Usage ─────────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("""
                Java SQL Parser — Hibernate Query Extractor & Rewriter
                ========================================================

                Usage:
                  java -jar parser.jar extract  <source-dir> [output-dir] [options]
                  java -jar parser.jar replace  <source-dir> <converted-sql-file> [registry-json] [options]
                  java -jar parser.jar report   <source-dir> [output-dir] [options]

                Commands:
                  extract  Parse Java sources, extract Hibernate/JPA/JdbcTemplate SQL, write:
                             output/queries.sql    — SQL with BEGIN_QID/END_QID markers for ora2pg
                             output/registry.json  — query metadata (ID, file, class, method, line, hash)
                             output/report.txt     — review report (Oracle constructs, language detection)

                  replace  Re-inject PostgreSQL SQL into Java source files after ora2pg:
                             Reads registry.json → locates original call sites → rewrites .java files
                             Creates .bak backups of each modified file

                  report   Extract and write the review report only (no SQL file written)

                Options:
                  --mode=all          Extract all detected queries (default)
                  --mode=oracle-only  Extract only queries containing Oracle-specific keywords

                  --exclude=<regex>   Skip Java files whose absolute path matches the regex
                                      Applied before parsing — entire file is excluded
                                      Examples:
                                        --exclude=.*Test.*
                                        --exclude=.*generated.*|.*legacy.*
                                        --exclude=.*(Test|Spec|IT)\\.java$

                Examples:
                  # Extract all queries
                  java -jar parser.jar extract src/main/java

                  # Extract only Oracle-tainted queries, skip test files
                  java -jar parser.jar extract src/main/java output --mode=oracle-only --exclude=.*Test.*

                  # Extract into custom output dir
                  java -jar parser.jar extract src/main/java output/sql --mode=all

                  # Re-inject after ora2pg conversion
                  java -jar parser.jar replace src/main/java output/converted.sql output/registry.json

                  # Review report only, oracle-only mode
                  java -jar parser.jar report src/main/java output --mode=oracle-only
                """);
    }
}
