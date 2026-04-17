package com.sqlparser.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses converted SQL files (output of ora2pg or manual conversion) to reconstruct
 * the mapping: QID → converted SQL.
 *
 * Reads the BEGIN_QID / END_QID markers written by SqlExporter and extracts
 * everything between them as the converted SQL for that query.
 */
public class SqlImporter {

    private static final Logger log = LoggerFactory.getLogger(SqlImporter.class);

    private static final Pattern BEGIN_PATTERN = Pattern.compile(
            "^--\\s*BEGIN_QID:(Q\\d+).*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern END_PATTERN = Pattern.compile(
            "^--\\s*END_QID:(Q\\d+).*$", Pattern.CASE_INSENSITIVE);

    // Lines to skip inside a QID block (metadata/warning comments injected by SqlExporter)
    private static final Pattern SKIP_LINE_PATTERN = Pattern.compile(
            "^--\\s*(REVIEW_REQUIRED|WARNING):.*$", Pattern.CASE_INSENSITIVE);

    /**
     * Parses a converted SQL file and returns a map of QID → converted SQL string.
     * The SQL string has trailing semicolons stripped, ready for embedding in Java string literals.
     */
    public Map<String, String> importConversions(Path convertedSqlFile) throws IOException {
        List<String> lines = Files.readAllLines(convertedSqlFile, java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();

        String currentId = null;
        List<String> buffer = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            Matcher beginMatcher = BEGIN_PATTERN.matcher(line.trim());
            if (beginMatcher.matches()) {
                currentId = beginMatcher.group(1);
                buffer.clear();
                continue;
            }

            Matcher endMatcher = END_PATTERN.matcher(line.trim());
            if (endMatcher.matches()) {
                String endId = endMatcher.group(1);
                if (currentId != null && currentId.equals(endId)) {
                    String sql = assembleSql(buffer);
                    if (!sql.isBlank()) {
                        result.put(currentId, sql);
                        log.debug("Imported conversion for {}: {} chars", currentId, sql.length());
                    } else {
                        log.warn("Empty SQL block for {} — skipping", currentId);
                    }
                } else {
                    log.warn("Mismatched END_QID: expected {} but found {} at line {}", currentId, endId, i + 1);
                }
                currentId = null;
                buffer.clear();
                continue;
            }

            // Inside a QID block — collect SQL lines, skip metadata comments
            if (currentId != null) {
                if (!SKIP_LINE_PATTERN.matcher(line.trim()).matches()) {
                    buffer.add(line);
                }
            }
        }

        if (currentId != null) {
            log.warn("Unclosed BEGIN_QID:{} — block was not terminated", currentId);
        }

        log.info("Imported {} conversions from: {}", result.size(), convertedSqlFile);
        return result;
    }

    /**
     * Imports multiple converted SQL files (e.g., per-query files) from a directory.
     * Each file must be named <QID>.sql (e.g., Q0001.sql).
     */
    public Map<String, String> importFromDirectory(Path directory) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();

        try (var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().matches("Q\\d+\\.sql"))
                  .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                  .forEach(file -> {
                      String qid = file.getFileName().toString().replace(".sql", "");
                      try {
                          // Per-query files also use markers; re-use importConversions
                          Map<String, String> single = importConversions(file);
                          if (single.containsKey(qid)) {
                              result.put(qid, single.get(qid));
                          } else {
                              // Fallback: treat entire file content as the SQL
                              String rawSql = assembleSql(Files.readAllLines(file));
                              if (!rawSql.isBlank()) {
                                  result.put(qid, rawSql);
                              }
                          }
                      } catch (IOException e) {
                          log.warn("Failed to read {}: {}", file, e.getMessage());
                      }
                  });
        }

        log.info("Imported {} per-query conversions from directory: {}", result.size(), directory);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String assembleSql(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            // Skip pure comment lines that are not part of SQL
            if (line.trim().startsWith("--")) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        String sql = sb.toString().trim();
        // Strip trailing semicolon — we'll add it back when needed
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }
}
