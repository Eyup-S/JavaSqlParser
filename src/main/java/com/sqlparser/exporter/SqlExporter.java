package com.sqlparser.exporter;

import com.sqlparser.model.OracleConstructDetector;
import com.sqlparser.model.OracleConstructDetector.OracleConstruct;
import com.sqlparser.model.QueryInfo;
import com.sqlparser.model.QueryLanguage;
import com.sqlparser.registry.QueryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Exports extracted SQL queries to files with QID markers for round-trip through ora2pg.
 *
 * Two export modes:
 *
 *   exportToFile(path)      — single combined file with all queries
 *   exportSplitFiles(dir)   — one file per (api, lang) combination
 *
 * Split file naming:
 *   queries_native_sql__native_sql.sql    ← needs ora2pg, API is correct
 *   queries_hql__native_sql.sql           ← needs ora2pg AND API fix (createQuery → createNativeQuery)
 *   queries_annotation__native_sql.sql    ← native @Query — needs ora2pg
 *   queries_hql__hql.sql                  ← pure HQL — no conversion needed
 *   queries_hql__ambiguous.sql            ← check manually
 *   queries_native_sql__ambiguous.sql     ← check manually
 *   ...
 *
 * Replacement side: pass any split file (after ora2pg) to the replace command.
 * Only the queries whose IDs appear in the converted file will be rewritten.
 * All other queries stay untouched — no extra flags needed.
 */
public class SqlExporter {

    private static final Logger log = LoggerFactory.getLogger(SqlExporter.class);

    private final QueryRegistry registry;

    public SqlExporter(QueryRegistry registry) {
        this.registry = registry;
    }

    // ── Single combined file ──────────────────────────────────────────────────

    /** Exports all registered queries to one SQL file. */
    public void exportToFile(Path outputFile) throws IOException {
        exportToFile(outputFile, registry.all(), "All queries");
    }

    /** Exports a specific subset of queries to one SQL file. */
    public void exportToFile(Path outputFile, Collection<QueryInfo> queries) throws IOException {
        exportToFile(outputFile, queries, "Queries");
    }

    private void exportToFile(Path outputFile, Collection<QueryInfo> queries,
                               String label) throws IOException {
        ensureParent(outputFile);

        try (PrintWriter out = openWriter(outputFile)) {
            out.println("-- ============================================================");
            out.println("-- Java SQL Parser — Extracted Hibernate/JPA Queries");
            out.printf ("-- %s%n", label);
            out.println("-- Generated: " + java.time.LocalDateTime.now());
            out.println("-- Total queries: " + queries.size());
            out.println("-- ============================================================");
            out.println();

            for (QueryInfo q : queries) {
                writeQueryBlock(out, q);
                out.println();
            }
        }

        log.info("Exported {} queries to: {}", queries.size(), outputFile);
    }

    // ── Split files ───────────────────────────────────────────────────────────

    /**
     * Exports one SQL file per (apiType × queryLanguage) combination into splitDir.
     * Empty combinations produce no file.
     *
     * Returns a summary map: filename → query count (for reporting).
     */
    public Map<String, Integer> exportSplitFiles(Path splitDir) throws IOException {
        Files.createDirectories(splitDir);

        // Group queries by combination key
        Map<String, List<QueryInfo>> groups = new LinkedHashMap<>();
        for (QueryInfo q : registry.all()) {
            String key = combinationKey(q.getQueryType(), q.getQueryLanguage());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(q);
        }

        Map<String, Integer> summary = new LinkedHashMap<>();

        for (Map.Entry<String, List<QueryInfo>> entry : groups.entrySet()) {
            String key   = entry.getKey();
            List<QueryInfo> subset = entry.getValue();
            Path file = splitDir.resolve("queries_" + key + ".sql");

            String label = labelFor(key);
            exportToFile(file, subset, label + " (" + subset.size() + " queries)");
            summary.put(file.getFileName().toString(), subset.size());
            log.info("Split file: {} ({} queries)", file.getFileName(), subset.size());
        }

        return summary;
    }

    /**
     * Returns the canonical combination key for a (apiType, lang) pair.
     * Used as the filename suffix.
     * e.g., NATIVE_SQL + NATIVE_SQL → "native_sql__native_sql"
     */
    public static String combinationKey(QueryInfo.QueryType apiType, QueryLanguage lang) {
        return apiType.name().toLowerCase(java.util.Locale.ROOT) + "__" + lang.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Human-readable label for a combination key, used in file headers.
     */
    public static String labelFor(String key) {
        return switch (key) {
            case "native_sql__native_sql"  -> "API:createNativeQuery + Lang:Native SQL  [needs ora2pg]";
            case "hql__native_sql"         -> "API:createQuery + Lang:Native SQL  [needs ora2pg AND API fix: use createNativeQuery]";
            case "annotation__native_sql"  -> "API:@Query(nativeQuery=true) + Lang:Native SQL  [needs ora2pg]";
            case "jdbc__native_sql"        -> "API:prepareStatement/executeQuery + Lang:Native SQL  [needs ora2pg]";
            case "jdbc__ambiguous"         -> "API:prepareStatement/executeQuery + Lang:Ambiguous  [check manually]";
            case "hql__hql"                -> "API:createQuery + Lang:HQL/JPQL  [no conversion needed]";
            case "annotation__hql"         -> "API:@Query + Lang:HQL/JPQL  [no conversion needed]";
            case "native_sql__hql"         -> "API:createNativeQuery + Lang:HQL  [review: HQL passed to native query]";
            case "hql__ambiguous"          -> "API:createQuery + Lang:Ambiguous  [check manually]";
            case "native_sql__ambiguous"   -> "API:createNativeQuery + Lang:Ambiguous  [check manually]";
            case "annotation__ambiguous"   -> "API:@Query + Lang:Ambiguous  [check manually]";
            default                        -> key;
        };
    }

    // ── Per-query files ───────────────────────────────────────────────────────

    /** Exports one .sql file per query (named by QID) into outputDir. */
    public void exportPerQuery(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        for (QueryInfo q : registry.all()) {
            Path queryFile = outputDir.resolve(q.getId() + ".sql");
            try (PrintWriter out = openWriter(queryFile)) {
                writeQueryBlock(out, q);
            }
        }

        log.info("Exported {} per-query files to: {}", registry.size(), outputDir);
    }

    // ── Review report ─────────────────────────────────────────────────────────

    public void exportReport(Path reportFile) throws IOException {
        ensureParent(reportFile);

        Collection<QueryInfo> queries = registry.all();
        long needsReview = queries.stream().filter(QueryInfo::isNeedsManualReview).count();
        long unresolved  = queries.stream()
                .filter(q -> q.getResolutionStatus() != QueryInfo.ResolutionStatus.RESOLVED).count();

        // Count by combination
        Map<String, Long> byCombination = queries.stream().collect(
                Collectors.groupingBy(
                        q -> combinationKey(q.getQueryType(), q.getQueryLanguage()),
                        Collectors.counting()));

        try (PrintWriter out = openWriter(reportFile)) {
            out.println("# Query Extraction Report");
            out.println("Generated: " + java.time.LocalDateTime.now());
            out.println();
            out.printf("Total queries      : %d%n", queries.size());
            out.printf("Needs manual review: %d%n", needsReview);
            out.printf("Unresolved/partial : %d%n", unresolved);
            out.println();

            out.println("## Breakdown by API × Language");
            out.println();
            out.printf("  %-45s %6s  %s%n", "Combination", "Count", "Action");
            out.println("  " + "-".repeat(90));

            // Print in a meaningful order
            List<String> orderedKeys = List.of(
                    "native_sql__native_sql",
                    "hql__native_sql",
                    "annotation__native_sql",
                    "jdbc__native_sql",
                    "native_sql__ambiguous",
                    "hql__ambiguous",
                    "annotation__ambiguous",
                    "jdbc__ambiguous",
                    "native_sql__hql",
                    "hql__hql",
                    "annotation__hql"
            );

            for (String key : orderedKeys) {
                long count = byCombination.getOrDefault(key, 0L);
                if (count == 0) continue;
                String action = conversionAction(key);
                out.printf("  %-45s %6d  %s%n", "queries_" + key + ".sql", count, action);
            }
            // Any unexpected combinations
            for (String key : byCombination.keySet()) {
                if (!orderedKeys.contains(key)) {
                    out.printf("  %-45s %6d%n", "queries_" + key + ".sql", byCombination.get(key));
                }
            }

            out.println();
            out.println("## Queries Needing Manual Review");
            out.println();

            for (QueryInfo q : queries) {
                if (!q.isNeedsManualReview()) continue;

                out.printf("### %s — %s:%s (line %d)%n",
                        q.getId(), q.getFile(), q.getMethod(), q.getLine());
                out.printf("  API type    : %s%n", q.getQueryType());
                out.printf("  SQL lang    : %s%n", q.getQueryLanguage());
                out.printf("  Resolution  : %s%n", q.getResolutionStatus());
                out.printf("  Oracle items: %s%n", q.getOracleConstructs());

                if (q.getResolvedSql() != null) {
                    for (OracleConstruct c : OracleConstructDetector.detectWithDetails(q.getResolvedSql())) {
                        out.printf("    [%s] %s → %s%n",
                                c.criticalForConversion() ? "CRITICAL" : "INFO",
                                c.name(), c.description());
                    }
                }
                out.println();
            }

            out.println("## All Queries");
            out.println();
            out.printf("%-8s %-38s %-24s %5s %-13s %-12s %-8s%n",
                    "ID", "File", "Method", "Line", "API Type", "Lang", "Review");
            out.println("-".repeat(120));

            for (QueryInfo q : queries) {
                String fileShort = q.getFile() != null ?
                        java.nio.file.Paths.get(q.getFile()).getFileName().toString() : "?";
                out.printf("%-8s %-38s %-24s %5d %-13s %-12s %-8s%n",
                        q.getId(),
                        truncate(fileShort, 38),
                        truncate(q.getMethod(), 24),
                        q.getLine(),
                        q.getQueryType(),
                        q.getQueryLanguage(),
                        q.isNeedsManualReview() ? "YES" : "no");
            }
        }

        log.info("Report written to: {}", reportFile);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void writeQueryBlock(PrintWriter out, QueryInfo q) {
        String fileShort = q.getFile() != null ?
                java.nio.file.Paths.get(q.getFile()).getFileName().toString() : "unknown";

        out.printf("-- BEGIN_QID:%s | file:%s | method:%s | line:%d | api:%s | lang:%s | hash:%s%n",
                q.getId(), fileShort, q.getMethod(), q.getLine(),
                q.getQueryType(), q.getQueryLanguage(), q.getHash());

        if (q.isNeedsManualReview()) {
            out.println("-- REVIEW_REQUIRED: " + String.join(", ", q.getOracleConstructs()));
        }
        if (q.getResolutionStatus() != QueryInfo.ResolutionStatus.RESOLVED) {
            out.println("-- WARNING: " + q.getResolutionStatus()
                    + " — some fragments could not be statically resolved");
        }

        String sql = q.getResolvedSql();
        if (sql == null || sql.isBlank()) {
            out.println("-- (empty / unresolved)");
        } else {
            String normalized = sql.trim();
            if (!normalized.endsWith(";")) normalized += ";";
            out.println(normalized);
        }

        out.printf("-- END_QID:%s%n", q.getId());
    }

    private static String conversionAction(String key) {
        return switch (key) {
            case "native_sql__native_sql" -> "Run ora2pg, then: replace src/ split/converted_native_sql__native_sql.sql";
            case "hql__native_sql"        -> "Run ora2pg + fix API (createQuery→createNativeQuery)";
            case "annotation__native_sql" -> "Run ora2pg";
            case "jdbc__native_sql"       -> "Run ora2pg, then: replace src/ split/converted_jdbc__native_sql.sql";
            case "hql__hql",
                 "annotation__hql"        -> "No conversion needed — skip";
            default                       -> "Review manually";
        };
    }

    private void ensureParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private PrintWriter openWriter(Path path) throws IOException {
        return new PrintWriter(new BufferedWriter(
                Files.newBufferedWriter(path, java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
