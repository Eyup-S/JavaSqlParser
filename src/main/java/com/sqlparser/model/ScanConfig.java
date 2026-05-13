package com.sqlparser.model;

import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Configuration object passed through the extraction pipeline.
 *
 * ScanMode:
 *   ALL         — extract every query regardless of content
 *   ORACLE_ONLY — extract only queries that contain at least one Oracle-specific keyword
 *
 * SourceMode:
 *   ALL             — extract Hibernate/JPA, JdbcTemplate, and raw JDBC
 *   HIBERNATE_ONLY  — skip raw JDBC (prepareStatement / executeQuery)
 *   JDBC_ONLY       — skip Hibernate/JPA, only extract raw JDBC
 *
 * appendMode:
 *   When true the caller pre-loads an existing registry.json before extraction.
 *   Already-registered locations are silently skipped so existing entries are not overwritten.
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

    public enum SourceMode {
        ALL,
        HIBERNATE_ONLY,
        JDBC_ONLY
    }

    private ScanMode mode;
    private SourceMode sourceMode;
    private boolean appendMode;
    private boolean scanYaml;
    private Pattern excludeFilePattern;
    private Set<String> customHqlMethods;
    private Set<String> customNativeMethods;

    private ScanConfig(ScanMode mode, SourceMode sourceMode, boolean appendMode, boolean scanYaml,
                       Pattern excludeFilePattern,
                       Set<String> customHqlMethods, Set<String> customNativeMethods) {
        this.mode = mode;
        this.sourceMode = sourceMode;
        this.appendMode = appendMode;
        this.scanYaml = scanYaml;
        this.excludeFilePattern = excludeFilePattern;
        this.customHqlMethods = customHqlMethods != null ? customHqlMethods : Collections.emptySet();
        this.customNativeMethods = customNativeMethods != null ? customNativeMethods : Collections.emptySet();
    }

    // ── Factory methods ───────────────────────────────────────────────────────

    public static ScanConfig allQueries() {
        return new ScanConfig(ScanMode.ALL, SourceMode.ALL, false, false, null, null, null);
    }

    public static ScanConfig oracleOnly() {
        return new ScanConfig(ScanMode.ORACLE_ONLY, SourceMode.ALL, false, false, null, null, null);
    }

    public static ScanConfig of(ScanMode mode, String excludeRegex) {
        return of(mode, SourceMode.ALL, excludeRegex, false, false, null, null);
    }

    public static ScanConfig of(ScanMode mode, SourceMode sourceMode, String excludeRegex, boolean appendMode) {
        return of(mode, sourceMode, excludeRegex, appendMode, false, null, null);
    }

    public static ScanConfig of(ScanMode mode, SourceMode sourceMode, String excludeRegex,
                                boolean appendMode, boolean scanYaml,
                                Set<String> customHqlMethods, Set<String> customNativeMethods) {
        Pattern pattern = null;
        if (excludeRegex != null && !excludeRegex.isBlank()) {
            try {
                pattern = Pattern.compile(excludeRegex);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid exclude pattern: " + excludeRegex, e);
            }
        }
        return new ScanConfig(mode, sourceMode, appendMode, scanYaml, pattern,
                customHqlMethods, customNativeMethods);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public ScanMode getMode() {
        return mode;
    }

    public SourceMode getSourceMode() {
        return sourceMode;
    }

    public boolean isAppendMode() {
        return appendMode;
    }

    public boolean isOracleOnly() {
        return mode == ScanMode.ORACLE_ONLY;
    }

    public boolean isScanYaml() {
        return scanYaml;
    }

    public Set<String> getCustomHqlMethods() {
        return customHqlMethods;
    }

    public Set<String> getCustomNativeMethods() {
        return customNativeMethods;
    }

    public boolean includesHibernate() {
        return sourceMode == SourceMode.ALL || sourceMode == SourceMode.HIBERNATE_ONLY;
    }

    public boolean includesJdbc() {
        return sourceMode == SourceMode.ALL || sourceMode == SourceMode.JDBC_ONLY;
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
               ", source=" + sourceMode +
               ", append=" + appendMode +
               ", yaml=" + scanYaml +
               ", customHql=" + customHqlMethods +
               ", customSql=" + customNativeMethods +
               ", exclude=" + (excludeFilePattern != null ? excludeFilePattern.pattern() : "none") + "}";
    }
}
