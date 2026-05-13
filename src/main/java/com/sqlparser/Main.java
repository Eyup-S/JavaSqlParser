package com.sqlparser;

import com.sqlparser.exporter.SqlExporter;
import com.sqlparser.extractor.QueryExtractor;
import com.sqlparser.extractor.YamlQueryExtractor;
import com.sqlparser.importer.SqlImporter;
import com.sqlparser.model.ScanConfig;
import com.sqlparser.parser.ParserModule;
import com.sqlparser.registry.QueryRegistry;
import com.sqlparser.rewriter.CodeRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

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

        // In append mode, load the existing registry first so duplicate locations are skipped
        if (config.isAppendMode()) {
            Path existingRegistry = outputDir.resolve("registry.json");
            if (Files.isRegularFile(existingRegistry)) {
                registry.loadFromJson(existingRegistry);
                log.info("Append mode: loaded {} existing queries from {}", registry.size(), existingRegistry);
            } else {
                log.warn("Append mode: no existing registry.json found at {} — starting fresh", existingRegistry);
            }
        }

        QueryExtractor extractor = new QueryExtractor(registry, config);
        extractor.extractAll(parsedFiles);

        // 2b. YAML scan (opt-in with --yaml)
        if (config.isScanYaml()) {
            YamlQueryExtractor yamlExtractor = new YamlQueryExtractor(registry, config);
            int yamlCount = yamlExtractor.extractAll(sourceDir);
            log.info("YAML scan: {} queries registered", yamlCount);
        }

        if (registry.size() == 0) {
            log.warn("No queries were found in {} (mode: {})", sourceDir, config.getMode());
        }

        // 3. Export SQL — combined file + split files by (api × lang)
        SqlExporter exporter = new SqlExporter(registry);
        Path sqlOutput  = outputDir.resolve("queries.sql");
        Path splitDir   = outputDir.resolve("split");
        Path registryJson = outputDir.resolve("registry.json");
        Path reportFile   = outputDir.resolve("report.txt");

        exporter.exportToFile(sqlOutput);
        Map<String, Integer> splitSummary = exporter.exportSplitFiles(splitDir);

        // 4. Save registry JSON
        registry.saveToJson(registryJson);

        // 5. Export report
        exporter.exportReport(reportFile);

        System.out.println();
        System.out.println("Done.");
        System.out.printf("  Mode           : %s%n", config.getMode());
        System.out.printf("  Source         : %s%n", config.getSourceMode());
        System.out.printf("  Append         : %s%n", config.isAppendMode());
        System.out.printf("  YAML scan      : %s%n", config.isScanYaml());
        if (!config.getCustomHqlMethods().isEmpty())
            System.out.printf("  Custom HQL     : %s%n", config.getCustomHqlMethods());
        if (!config.getCustomNativeMethods().isEmpty())
            System.out.printf("  Custom SQL     : %s%n", config.getCustomNativeMethods());
        System.out.printf("  Queries total  : %d%n", registry.size());
        System.out.printf("  Combined SQL   : %s%n", sqlOutput.toAbsolutePath());
        System.out.printf("  Registry JSON  : %s%n", registryJson.toAbsolutePath());
        System.out.printf("  Report         : %s%n", reportFile.toAbsolutePath());
        System.out.println();
        System.out.println("Split files (by API × Language):");
        splitSummary.forEach((file, count) ->
                System.out.printf("  %-55s  %d queries%n",
                        splitDir.resolve(file).toAbsolutePath(), count));
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("  1. Run ora2pg on the split files that need conversion (see report.txt).");
        System.out.println("     Preserve the -- BEGIN_QID / END_QID comment lines.");
        System.out.println("  2. For each converted file, re-inject:");
        System.out.printf ("     java -jar parser.jar replace %s <converted_split_file.sql> %s%n",
                sourceDir, registryJson);
        System.out.println("     Only queries present in the converted file will be rewritten.");
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

        if (config.isScanYaml()) {
            new YamlQueryExtractor(registry, config).extractAll(sourceDir);
        }

        SqlExporter exporter = new SqlExporter(registry);
        Path reportFile = outputDir.resolve("report.txt");
        exporter.exportReport(reportFile);

        System.out.printf("Report written to  : %s%n", reportFile.toAbsolutePath());
        System.out.printf("Queries found      : %d  (mode=%s)%n", registry.size(), config.getMode());
    }

    // ── Argument parsing ──────────────────────────────────────────────────────

    /**
     * Parses --mode, --source, --append, and --exclude flags from anywhere in the args array.
     */
    private static ScanConfig parseScanConfig(String[] args) {
        ScanConfig.ScanMode mode = ScanConfig.ScanMode.ALL;
        ScanConfig.SourceMode sourceMode = ScanConfig.SourceMode.ALL;
        String excludePattern = null;
        boolean appendMode = false;
        boolean scanYaml = false;
        Set<String> customHqlMethods = new LinkedHashSet<>();
        Set<String> customNativeMethods = new LinkedHashSet<>();

        for (String arg : args) {
            if (arg.startsWith("--mode=")) {
                String val = arg.substring("--mode=".length()).trim().toLowerCase();
                mode = switch (val) {
                    case "oracle-only", "oracle_only", "oracleonly" -> ScanConfig.ScanMode.ORACLE_ONLY;
                    case "all" -> ScanConfig.ScanMode.ALL;
                    default -> throw new IllegalArgumentException(
                            "Unknown mode '" + val + "'. Use: --mode=all  or  --mode=oracle-only");
                };
            } else if (arg.startsWith("--source=")) {
                String val = arg.substring("--source=".length()).trim().toLowerCase();
                sourceMode = switch (val) {
                    case "all"      -> ScanConfig.SourceMode.ALL;
                    case "hibernate", "hibernate-only" -> ScanConfig.SourceMode.HIBERNATE_ONLY;
                    case "jdbc", "jdbc-only"           -> ScanConfig.SourceMode.JDBC_ONLY;
                    default -> throw new IllegalArgumentException(
                            "Unknown source '" + val + "'. Use: --source=all  --source=hibernate  --source=jdbc");
                };
            } else if (arg.equals("--append")) {
                appendMode = true;
            } else if (arg.equals("--yaml")) {
                scanYaml = true;
            } else if (arg.startsWith("--custom-hql=")) {
                String val = arg.substring("--custom-hql=".length()).trim();
                Arrays.stream(val.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                      .forEach(customHqlMethods::add);
            } else if (arg.startsWith("--custom-sql=")) {
                String val = arg.substring("--custom-sql=".length()).trim();
                Arrays.stream(val.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                      .forEach(customNativeMethods::add);
            } else if (arg.startsWith("--exclude=")) {
                excludePattern = arg.substring("--exclude=".length());
            }
        }

        return ScanConfig.of(mode, sourceMode, excludePattern, appendMode, scanYaml,
                customHqlMethods.isEmpty() ? null : customHqlMethods,
                customNativeMethods.isEmpty() ? null : customNativeMethods);
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

                  --source=all        Extract Hibernate/JPA, JdbcTemplate, and raw JDBC (default)
                  --source=hibernate  Extract only Hibernate/JPA and JdbcTemplate calls
                  --source=jdbc       Extract only raw JDBC (prepareStatement, executeQuery, ...)

                  --append            Append mode: load existing registry.json first, skip already-known
                                      locations — use when you want to add JDBC queries to an existing
                                      Hibernate-only registry without losing prior review state

                  --yaml              Also scan .yaml/.yml files in the source directory for SQL strings
                                      Uses the YAML key path as the method name (e.g. "queries.findAll.sql")

                  --custom-hql=m1,m2  Treat these method names as HQL queries (first arg is HQL string)
                                      e.g. --custom-hql=executeHql,runHqlQuery,findByHql

                  --custom-sql=m1,m2  Treat these method names as native SQL queries (first arg is SQL)
                                      e.g. --custom-sql=executeSql,runNative,executeNativeQuery

                  --exclude=<regex>   Skip Java files whose absolute path matches the regex
                                      Applied before parsing — entire file is excluded
                                      Examples:
                                        --exclude=.*Test.*
                                        --exclude=.*generated.*|.*legacy.*
                                        --exclude=.*(Test|Spec|IT)\\.java$

                Examples:
                  # Extract all queries (Hibernate + JdbcTemplate + JDBC)
                  java -jar parser.jar extract src/main/java

                  # Extract only Oracle-tainted queries, skip test files
                  java -jar parser.jar extract src/main/java output --mode=oracle-only --exclude=.*Test.*

                  # First run — Hibernate only
                  java -jar parser.jar extract src/main/java output --source=hibernate

                  # Second run — append JDBC queries to same registry
                  java -jar parser.jar extract src/main/java output --source=jdbc --append

                  # Scan YAML files alongside Java
                  java -jar parser.jar extract src/main/java output --yaml

                  # Custom methods — detect your own HQL/SQL runner methods
                  java -jar parser.jar extract src/main/java output --custom-hql=executeHql,runHql --custom-sql=executeSql,runNative

                  # Re-inject after ora2pg conversion
                  java -jar parser.jar replace src/main/java output/converted.sql output/registry.json

                  # Review report only, oracle-only mode
                  java -jar parser.jar report src/main/java output --mode=oracle-only
                """);
    }
}
