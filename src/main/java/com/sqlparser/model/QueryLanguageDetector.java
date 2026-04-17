package com.sqlparser.model;

import com.sqlparser.extractor.SqlStringResolver;

import java.util.regex.Pattern;

/**
 * Determines whether a query string is HQL/JPQL or native SQL using heuristics.
 *
 * Applied when the API call alone is ambiguous — e.g., createQuery() accepts both.
 *
 * Detection priority (first match wins):
 *   1. Oracle-specific keyword present          → NATIVE_SQL (strongest signal)
 *   2. SQL syntax patterns (SELECT *, table.col)→ NATIVE_SQL
 *   3. Table-style FROM (snake_case / ALL_CAPS) → NATIVE_SQL
 *   4. Oracle join syntax (+)                   → NATIVE_SQL
 *   5. Entity-style FROM (PascalCase class name)→ HQL
 *   6. Alias.property in SELECT                 → HQL
 *   7. NEW keyword (JPA constructor expression) → HQL
 *   8. Fallback                                 → AMBIGUOUS
 */
public class QueryLanguageDetector {

    // ── Native SQL patterns ───────────────────────────────────────────────────

    /** SELECT * or SELECT t.* */
    private static final Pattern SELECT_STAR = Pattern.compile(
            "\\bSELECT\\s+(\\w+\\.)?\\*", Pattern.CASE_INSENSITIVE);

    /** FROM snake_case_table or FROM ALL_CAPS_TABLE (not a Java class name) */
    private static final Pattern FROM_TABLE = Pattern.compile(
            "\\bFROM\\s+([a-z][a-z0-9_]+|[A-Z][A-Z0-9_]+)\\b", Pattern.CASE_INSENSITIVE);

    /** Oracle outer join syntax: table.col(+) */
    private static final Pattern ORACLE_OUTER_JOIN = Pattern.compile(
            "\\(\\s*\\+\\s*\\)");

    /** Column or table with underscore — table_name style, not entityName */
    private static final Pattern UNDERSCORE_NAME = Pattern.compile(
            "\\b[a-z]+_[a-z0-9_]+\\b");

    /** Raw SQL keywords that don't appear in JPQL (e.g. column aliases, raw SQL clauses) */
    private static final Pattern RAW_SQL_KEYWORDS = Pattern.compile(
            "\\b(HAVING|UNION|EXCEPT|INTERSECT|MINUS|TRUNCATE|INSERT\\s+INTO|" +
            "CREATE\\s+TABLE|DROP\\s+TABLE|ALTER\\s+TABLE|DUAL|ROWNUM|ROWID)\\b",
            Pattern.CASE_INSENSITIVE);

    // ── HQL / JPQL patterns ───────────────────────────────────────────────────

    /**
     * FROM PascalCase — entity class name (Java naming convention).
     * e.g., FROM User u, FROM OrderItem oi, FROM com.example.User u
     */
    private static final Pattern FROM_ENTITY = Pattern.compile(
            "\\bFROM\\s+([a-z][a-z0-9]*\\.)*[A-Z][a-zA-Z0-9]+(\\s+\\w+)?\\b");

    /**
     * SELECT alias.property in SELECT clause — typical HQL alias.field pattern.
     * e.g., SELECT u.name, u.createdAt
     */
    private static final Pattern SELECT_ALIAS_PROPERTY = Pattern.compile(
            "\\bSELECT\\s+(DISTINCT\\s+)?[a-z]\\w*\\.[a-zA-Z]\\w*\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * WHERE alias.camelCaseProperty — HQL uses mapped field names, SQL uses column names.
     * e.g., WHERE u.createdAt > :date
     */
    private static final Pattern CAMELPROP_IN_WHERE = Pattern.compile(
            "\\b[a-z]\\w*\\.[a-z][a-zA-Z0-9]*[A-Z][a-zA-Z0-9]*\\b");

    /** JPA constructor expression: NEW com.example.dto.UserDto(u.id, u.name) */
    private static final Pattern JPA_NEW = Pattern.compile(
            "\\bNEW\\s+[a-zA-Z][\\w.]*\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** JOIN FETCH — HQL-specific eager loading syntax */
    private static final Pattern JOIN_FETCH = Pattern.compile(
            "\\bJOIN\\s+FETCH\\b", Pattern.CASE_INSENSITIVE);

    /** Named parameter style :paramName — used in both but camelCase hints at HQL */
    private static final Pattern NAMED_PARAM_CAMEL = Pattern.compile(
            ":\\s*[a-z][a-zA-Z]+[A-Z]");

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Detects the query language for a query string when the API type alone is ambiguous.
     * For QueryType.NATIVE_SQL (createNativeQuery), this method should not be needed —
     * just return QueryLanguage.NATIVE_SQL directly.
     */
    public static QueryLanguage detect(String sql, QueryInfo.QueryType apiType) {
        // API-level certainties
        if (apiType == QueryInfo.QueryType.NATIVE_SQL) {
            return QueryLanguage.NATIVE_SQL;
        }

        if (sql == null || sql.isBlank() || sql.contains(SqlStringResolver.UNRESOLVED_MARKER)) {
            return QueryLanguage.AMBIGUOUS;
        }

        String trimmed = sql.trim();

        // ── Priority 1: Oracle keyword → always native SQL ────────────────
        if (!OracleConstructDetector.detect(trimmed).isEmpty()) {
            return QueryLanguage.NATIVE_SQL;
        }

        // ── Priority 2: Oracle outer join syntax ──────────────────────────
        if (ORACLE_OUTER_JOIN.matcher(trimmed).find()) {
            return QueryLanguage.NATIVE_SQL;
        }

        // ── Priority 3: Raw SQL-only keywords ────────────────────────────
        if (RAW_SQL_KEYWORDS.matcher(trimmed).find()) {
            return QueryLanguage.NATIVE_SQL;
        }

        // ── Priority 4: HQL-specific → strong signal ─────────────────────
        if (JPA_NEW.matcher(trimmed).find()) return QueryLanguage.HQL;
        if (JOIN_FETCH.matcher(trimmed).find()) return QueryLanguage.HQL;

        // ── Priority 5: SELECT * → native SQL ────────────────────────────
        if (SELECT_STAR.matcher(trimmed).find()) {
            return QueryLanguage.NATIVE_SQL;
        }

        // ── Priority 6: Entity-style FROM (PascalCase) → HQL ─────────────
        if (FROM_ENTITY.matcher(trimmed).find()) {
            return QueryLanguage.HQL;
        }

        // ── Priority 7: Table-style FROM (snake_case or ALL_CAPS) → SQL ──
        if (FROM_TABLE.matcher(trimmed).find()) {
            // Extra check: does it look like a table name (underscore or all-caps)?
            java.util.regex.Matcher m = FROM_TABLE.matcher(trimmed);
            while (m.find()) {
                String name = m.group(1);
                if (name.contains("_") || name.equals(name.toUpperCase())) {
                    return QueryLanguage.NATIVE_SQL;
                }
            }
        }

        // ── Priority 8: camelCase property in WHERE → HQL ─────────────────
        if (CAMELPROP_IN_WHERE.matcher(trimmed).find()) {
            return QueryLanguage.HQL;
        }

        // ── Priority 9: alias.property SELECT → HQL ───────────────────────
        if (SELECT_ALIAS_PROPERTY.matcher(trimmed).find()) {
            return QueryLanguage.HQL;
        }

        // ── Priority 10: underscore names in SQL body → SQL ───────────────
        if (UNDERSCORE_NAME.matcher(trimmed).find()) {
            return QueryLanguage.NATIVE_SQL;
        }

        return QueryLanguage.AMBIGUOUS;
    }
}
