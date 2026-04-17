package com.sqlparser.model;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configuration object passed through the extraction pipeline.
 *
 * ScanMode:
 *   ALL         — extract every query regardless of content
 *   ORACLE_ONLY — extract only queries that contain at least one Oracle-specific keyword
 *
 * excludeFilePattern:
 *   Regex applied to the absolute file path. Files matching the pattern are skipped entirely.
 *   Example: ".*Test.*"  or  ".*generated.*|.*legacy.*"
 */
public class ScanConfig {

    public enum ScanMode {
        ALL,
        ORACLE_ONLY
    }

    private ScanMode mode;
    private Pattern excludeFilePattern;

    private ScanConfig(ScanMode mode, Pattern excludeFilePattern) {
        this.mode = mode;
        this.excludeFilePattern = excludeFilePattern;
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static ScanConfig allQueries() {
        return new ScanConfig(ScanMode.ALL, null);
    }

    public static ScanConfig oracleOnly() {
        return new ScanConfig(ScanMode.ORACLE_ONLY, null);
    }

    public static ScanConfig of(ScanMode mode, String excludeRegex) {
        Pattern pattern = null;
        if (excludeRegex != null && !excludeRegex.isBlank()) {
            try {
                pattern = Pattern.compile(excludeRegex);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid exclude pattern: " + excludeRegex, e);
            }
        }
        return new ScanConfig(mode, pattern);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public ScanMode getMode() {
        return mode;
    }

    public boolean isOracleOnly() {
        return mode == ScanMode.ORACLE_ONLY;
    }

    /**
     * Returns true if the given file path should be excluded from scanning.
     */
    public boolean shouldExclude(String filePath) {
        if (excludeFilePattern == null) return false;
        return excludeFilePattern.matcher(filePath).find();
    }

    /**
     * Returns true if the given resolved SQL should be kept under the current mode.
     * In ALL mode: always true.
     * In ORACLE_ONLY mode: true only when the SQL contains at least one Oracle keyword.
     */
    public boolean shouldInclude(String resolvedSql) {
        if (mode == ScanMode.ALL) return true;
        return OracleConstructDetector.hasAnyOracleKeyword(resolvedSql);
    }

    @Override
    public String toString() {
        return "ScanConfig{mode=" + mode +
               ", exclude=" + (excludeFilePattern != null ? excludeFilePattern.pattern() : "none") + "}";
    }
}
