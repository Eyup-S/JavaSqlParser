package com.sqlparser.exporter;

import com.sqlparser.model.OracleConstructDetector;
import com.sqlparser.model.OracleConstructDetector.OracleConstruct;
import com.sqlparser.model.QueryInfo;
import com.sqlparser.registry.QueryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Exports extracted SQL queries to files with QID markers so they can be
 * round-tripped through ora2pg and re-imported.
 *
 * Single-file output format:
 *
 *   -- BEGIN_QID:Q0001 | file:UserRepo.java | method:findActive | line:42 | type:NATIVE_SQL
 *   SELECT * FROM users WHERE created_at > SYSDATE;
 *   -- END_QID:Q0001
 */
public class SqlExporter {

    private static final Logger log = LoggerFactory.getLogger(SqlExporter.class);

    public enum Mode {
        SINGLE_FILE,  // all queries in one .sql file
        PER_QUERY     // one .sql file per query in a directory
    }

    private final QueryRegistry registry;

    public SqlExporter(QueryRegistry registry) {
        this.registry = registry;
    }

    /**
     * Exports all registered queries to a single SQL file.
     */
    public void exportToFile(Path outputFile) throws IOException {
        exportToFile(outputFile, registry.all());
    }

    /**
     * Exports a specific set of queries to a single SQL file.
     */
    public void exportToFile(Path outputFile, Collection<QueryInfo> queries) throws IOException {
        Files.createDirectories(outputFile.getParent() != null ? outputFile.getParent() : Path.of("."));

        try (PrintWriter out = new PrintWriter(new BufferedWriter(
                Files.newBufferedWriter(outputFile, java.nio.charset.StandardCharsets.UTF_8)))) {

            out.println("-- ============================================================");
            out.println("-- Java SQL Parser — Extracted Hibernate/JPA Queries");
            out.println("-- Generated: " + java.time.LocalDateTime.now());
            out.println("-- Total queries: " + queries.size());
            out.println("-- ============================================================");
            out.println();

            int exported = 0;
            for (QueryInfo q : queries) {
                writeQueryBlock(out, q);
                out.println();
                exported++;
            }

            log.info("Exported {} queries to: {}", exported, outputFile);
        }
    }

    /**
     * Exports each query to an individual .sql file inside the given directory.
     */
    public void exportPerQuery(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        for (QueryInfo q : registry.all()) {
            Path queryFile = outputDir.resolve(q.getId() + ".sql");
            try (PrintWriter out = new PrintWriter(new BufferedWriter(
                    Files.newBufferedWriter(queryFile, java.nio.charset.StandardCharsets.UTF_8)))) {
                writeQueryBlock(out, q);
            }
        }

        log.info("Exported {} individual query files to: {}", registry.size(), outputDir);
    }

    /**
     * Exports a summary report of all queries with their Oracle constructs.
     */
    public void exportReport(Path reportFile) throws IOException {
        Files.createDirectories(reportFile.getParent() != null ? reportFile.getParent() : Path.of("."));

        Collection<QueryInfo> queries = registry.all();
        long needsReview = queries.stream().filter(QueryInfo::isNeedsManualReview).count();
        long unresolved = queries.stream()
                .filter(q -> q.getResolutionStatus() != QueryInfo.ResolutionStatus.RESOLVED)
                .count();
        long nativeSql  = queries.stream()
                .filter(q -> q.getQueryLanguage() == com.sqlparser.model.QueryLanguage.NATIVE_SQL)
                .count();
        long hql        = queries.stream()
                .filter(q -> q.getQueryLanguage() == com.sqlparser.model.QueryLanguage.HQL)
                .count();
        long ambiguous  = queries.stream()
                .filter(q -> q.getQueryLanguage() == com.sqlparser.model.QueryLanguage.AMBIGUOUS)
                .count();

        try (PrintWriter out = new PrintWriter(new BufferedWriter(
                Files.newBufferedWriter(reportFile, java.nio.charset.StandardCharsets.UTF_8)))) {

            out.println("# Query Extraction Report");
            out.println("Generated: " + java.time.LocalDateTime.now());
            out.println();
            out.printf("Total queries      : %d%n", queries.size());
            out.printf("  Native SQL       : %d%n", nativeSql);
            out.printf("  HQL/JPQL         : %d%n", hql);
            out.printf("  Ambiguous        : %d%n", ambiguous);
            out.printf("Needs manual review: %d%n", needsReview);
            out.printf("Unresolved/partial : %d%n", unresolved);
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
                    List<OracleConstruct> details = OracleConstructDetector.detectWithDetails(q.getResolvedSql());
                    for (OracleConstruct c : details) {
                        out.printf("    [%s] %s → %s%n",
                                c.criticalForConversion() ? "CRITICAL" : "INFO",
                                c.name(), c.description());
                    }
                }
                out.println();
            }

            out.println("## All Queries");
            out.println();
            out.printf("%-8s %-40s %-25s %6s %-13s %-12s %-8s%n",
                    "ID", "File", "Method", "Line", "API Type", "Lang", "Review");
            out.println("-".repeat(125));

            for (QueryInfo q : queries) {
                String fileShort = q.getFile() != null ?
                        java.nio.file.Paths.get(q.getFile()).getFileName().toString() : "?";
                out.printf("%-8s %-40s %-25s %6d %-13s %-12s %-8s%n",
                        q.getId(),
                        truncate(fileShort, 40),
                        truncate(q.getMethod(), 25),
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

        // Begin marker — carries all metadata in the comment so it survives ora2pg
        out.printf("-- BEGIN_QID:%s | file:%s | method:%s | line:%d | api:%s | lang:%s | hash:%s%n",
                q.getId(), fileShort, q.getMethod(), q.getLine(),
                q.getQueryType(), q.getQueryLanguage(), q.getHash());

        if (q.isNeedsManualReview()) {
            out.println("-- REVIEW_REQUIRED: " + String.join(", ", q.getOracleConstructs()));
        }

        if (q.getResolutionStatus() != QueryInfo.ResolutionStatus.RESOLVED) {
            out.println("-- WARNING: " + q.getResolutionStatus() + " — some fragments could not be statically resolved");
        }

        String sql = q.getResolvedSql();
        if (sql == null || sql.isBlank()) {
            out.println("-- (empty / unresolved)");
        } else {
            // Normalize: ensure single trailing semicolon
            String normalized = sql.trim();
            if (!normalized.endsWith(";")) normalized += ";";
            out.println(normalized);
        }

        out.printf("-- END_QID:%s%n", q.getId());
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
