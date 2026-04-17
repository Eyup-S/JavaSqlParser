package com.sqlparser.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects Oracle-specific SQL constructs in a resolved SQL string.
 *
 * Keyword categories from the project spec:
 *   - Date & time
 *   - Null & conditional
 *   - Conversion
 *   - String functions
 *   - Regex functions
 *   - Aggregation
 *   - Analytical / window functions
 *   - Hierarchical query
 *   - Special tables / pseudocolumns
 *   - Set operations
 *   - Join syntax
 *   - Sequences
 *   - Miscellaneous
 *
 * Each construct carries:
 *   name                — short label used in reports and QueryInfo.oracleConstructs
 *   pattern             — regex matched case-insensitively
 *   description         — the PostgreSQL equivalent / migration note
 *   criticalForConversion — true if the construct breaks or changes semantics in PostgreSQL
 */
public class OracleConstructDetector {

    public record OracleConstruct(
            String name,
            Pattern pattern,
            String description,
            boolean criticalForConversion) {}

    private static final List<OracleConstruct> CONSTRUCTS = List.of(

        // ── Date & time ───────────────────────────────────────────────────────

        new OracleConstruct("SYSDATE",
            word("SYSDATE"),
            "Oracle SYSDATE → PostgreSQL CURRENT_TIMESTAMP or NOW()", true),

        new OracleConstruct("SYSTIMESTAMP",
            word("SYSTIMESTAMP"),
            "Oracle SYSTIMESTAMP → PostgreSQL CURRENT_TIMESTAMP(6) or clock_timestamp()", true),

        new OracleConstruct("TRUNC",
            func("TRUNC"),
            "Oracle TRUNC(date) or TRUNC(number) → PostgreSQL DATE_TRUNC() or TRUNC()", true),

        new OracleConstruct("ADD_MONTHS",
            func("ADD_MONTHS"),
            "Oracle ADD_MONTHS(d,n) → PostgreSQL d + INTERVAL 'n months'", true),

        new OracleConstruct("MONTHS_BETWEEN",
            func("MONTHS_BETWEEN"),
            "Oracle MONTHS_BETWEEN(d1,d2) → PostgreSQL EXTRACT(MONTH FROM AGE(d1,d2)) — approximate", true),

        new OracleConstruct("LAST_DAY",
            func("LAST_DAY"),
            "Oracle LAST_DAY(d) → PostgreSQL DATE_TRUNC('month',d) + INTERVAL '1 month' - INTERVAL '1 day'", true),

        new OracleConstruct("NEXT_DAY",
            func("NEXT_DAY"),
            "Oracle NEXT_DAY(d, 'MONDAY') — no direct PostgreSQL equivalent; needs custom logic", true),

        // CURRENT_DATE, CURRENT_TIMESTAMP, LOCALTIMESTAMP are SQL standard but
        // Oracle's behaviour can differ slightly (session timezone handling)
        new OracleConstruct("LOCALTIMESTAMP",
            word("LOCALTIMESTAMP"),
            "Oracle LOCALTIMESTAMP → PostgreSQL LOCALTIMESTAMP (standard; verify timezone semantics)", false),

        // ── Null & conditional ────────────────────────────────────────────────

        new OracleConstruct("NVL",
            func("NVL"),
            "Oracle NVL(expr, default) → PostgreSQL COALESCE(expr, default)", true),

        new OracleConstruct("NVL2",
            func("NVL2"),
            "Oracle NVL2(expr,if_not_null,if_null) → PostgreSQL CASE WHEN expr IS NOT NULL THEN ... END", true),

        new OracleConstruct("DECODE",
            func("DECODE"),
            "Oracle DECODE(expr,v1,r1,...,default) → PostgreSQL CASE WHEN ... THEN ... END", true),

        new OracleConstruct("NULLIF",
            func("NULLIF"),
            "Oracle NULLIF() → PostgreSQL NULLIF() (SQL standard — compatible)", false),

        // ── Conversion ────────────────────────────────────────────────────────

        new OracleConstruct("TO_DATE",
            func("TO_DATE"),
            "Oracle TO_DATE(str, fmt) → PostgreSQL TO_DATE(str, fmt) — mostly compatible; check Oracle format masks", false),

        new OracleConstruct("TO_CHAR",
            func("TO_CHAR"),
            "Oracle TO_CHAR() → PostgreSQL TO_CHAR() — mostly compatible; verify format strings", false),

        new OracleConstruct("TO_NUMBER",
            func("TO_NUMBER"),
            "Oracle TO_NUMBER() → PostgreSQL TO_NUMBER() or CAST(... AS NUMERIC)", false),

        new OracleConstruct("CAST",
            func("CAST"),
            "Oracle CAST() — SQL standard; usually compatible but verify Oracle-specific type names", false),

        // ── String functions ──────────────────────────────────────────────────

        new OracleConstruct("INSTR",
            func("INSTR"),
            "Oracle INSTR(str,sub[,pos[,n]]) → PostgreSQL POSITION(sub IN str) or STRPOS() — multi-arg form needs rewrite", true),

        new OracleConstruct("SUBSTR",
            func("SUBSTR"),
            "Oracle SUBSTR(str,pos,len) → PostgreSQL SUBSTRING(str FROM pos FOR len)", false),

        new OracleConstruct("LENGTH",
            func("LENGTH"),
            "Oracle LENGTH() → PostgreSQL LENGTH() (compatible)", false),

        new OracleConstruct("LPAD",
            func("LPAD"),
            "Oracle LPAD() → PostgreSQL LPAD() (compatible)", false),

        new OracleConstruct("RPAD",
            func("RPAD"),
            "Oracle RPAD() → PostgreSQL RPAD() (compatible)", false),

        new OracleConstruct("RTRIM",
            func("RTRIM"),
            "Oracle RTRIM() → PostgreSQL RTRIM() (compatible)", false),

        new OracleConstruct("LTRIM",
            func("LTRIM"),
            "Oracle LTRIM() → PostgreSQL LTRIM() (compatible)", false),

        // ── Regex functions ───────────────────────────────────────────────────

        new OracleConstruct("REGEXP_LIKE",
            func("REGEXP_LIKE"),
            "Oracle REGEXP_LIKE(str,pat[,flags]) → PostgreSQL str ~ pat (or ~* for case-insensitive)", true),

        new OracleConstruct("REGEXP_SUBSTR",
            func("REGEXP_SUBSTR"),
            "Oracle REGEXP_SUBSTR() → PostgreSQL SUBSTRING(str FROM pattern) — syntax differs significantly", true),

        new OracleConstruct("REGEXP_INSTR",
            func("REGEXP_INSTR"),
            "Oracle REGEXP_INSTR() — no direct PostgreSQL equivalent; use custom function or REGEXP_MATCHES", true),

        new OracleConstruct("REGEXP_REPLACE",
            func("REGEXP_REPLACE"),
            "Oracle REGEXP_REPLACE(src,pat,repl[,pos,n,flags]) → PostgreSQL REGEXP_REPLACE(src,pat,repl[,flags])", true),

        // ── Aggregation ───────────────────────────────────────────────────────

        new OracleConstruct("LISTAGG",
            func("LISTAGG"),
            "Oracle LISTAGG(col, delim) WITHIN GROUP (ORDER BY ...) → PostgreSQL STRING_AGG(col, delim ORDER BY ...)", true),

        // ── Analytical / window functions ─────────────────────────────────────

        new OracleConstruct("LAG",
            func("LAG"),
            "Oracle LAG() OVER() → PostgreSQL LAG() OVER() (SQL standard — compatible)", false),

        new OracleConstruct("LEAD",
            func("LEAD"),
            "Oracle LEAD() OVER() → PostgreSQL LEAD() OVER() (SQL standard — compatible)", false),

        new OracleConstruct("RANK",
            func("RANK"),
            "Oracle RANK() OVER() → PostgreSQL RANK() OVER() (SQL standard — compatible)", false),

        new OracleConstruct("DENSE_RANK",
            func("DENSE_RANK"),
            "Oracle DENSE_RANK() OVER() → PostgreSQL DENSE_RANK() OVER() (SQL standard — compatible)", false),

        new OracleConstruct("ROW_NUMBER",
            func("ROW_NUMBER"),
            "Oracle ROW_NUMBER() OVER() → PostgreSQL ROW_NUMBER() OVER() (SQL standard — compatible)", false),

        // ── Hierarchical query ────────────────────────────────────────────────

        new OracleConstruct("CONNECT BY",
            Pattern.compile("\\bCONNECT\\s+BY\\b", Pattern.CASE_INSENSITIVE),
            "Oracle CONNECT BY / START WITH hierarchical query → PostgreSQL WITH RECURSIVE", true),

        new OracleConstruct("START WITH",
            Pattern.compile("\\bSTART\\s+WITH\\b", Pattern.CASE_INSENSITIVE),
            "Oracle START WITH (hierarchical query root condition) → part of WITH RECURSIVE rewrite", true),

        new OracleConstruct("LEVEL",
            word("LEVEL"),
            "Oracle LEVEL pseudocolumn (depth in hierarchy) → available in PostgreSQL WITH RECURSIVE", true),

        // ── Special tables / pseudocolumns ────────────────────────────────────

        new OracleConstruct("DUAL",
            Pattern.compile("\\bFROM\\s+DUAL\\b", Pattern.CASE_INSENSITIVE),
            "Oracle FROM DUAL → PostgreSQL: remove or use SELECT expr directly (no FROM needed)", false),

        new OracleConstruct("ROWNUM",
            word("ROWNUM"),
            "Oracle ROWNUM → PostgreSQL LIMIT n or ROW_NUMBER() OVER() — ordering semantics differ", true),

        new OracleConstruct("ROWID",
            word("ROWID"),
            "Oracle ROWID physical address pseudocolumn — PostgreSQL equivalent: ctid (internal, unstable)", true),

        // ── Set operations ────────────────────────────────────────────────────

        new OracleConstruct("MINUS",
            // Avoid false-positive on "MINUS" inside identifiers; require word boundary
            Pattern.compile("\\bMINUS\\b(?!\\s*=)", Pattern.CASE_INSENSITIVE),
            "Oracle MINUS set operator → PostgreSQL EXCEPT", true),

        new OracleConstruct("INTERSECT",
            word("INTERSECT"),
            "Oracle INTERSECT → PostgreSQL INTERSECT (SQL standard — compatible)", false),

        // ── Join syntax ───────────────────────────────────────────────────────

        new OracleConstruct("ORACLE_OUTER_JOIN",
            Pattern.compile("\\(\\s*\\+\\s*\\)"),
            "Oracle (+) outer join syntax → PostgreSQL LEFT JOIN / RIGHT JOIN (ANSI syntax)", true),

        // ── Sequences ────────────────────────────────────────────────────────

        new OracleConstruct("NEXTVAL",
            Pattern.compile("\\b\\w+\\.NEXTVAL\\b", Pattern.CASE_INSENSITIVE),
            "Oracle seq.NEXTVAL → PostgreSQL NEXTVAL('seq_name')", true),

        new OracleConstruct("CURRVAL",
            Pattern.compile("\\b\\w+\\.CURRVAL\\b", Pattern.CASE_INSENSITIVE),
            "Oracle seq.CURRVAL → PostgreSQL CURRVAL('seq_name')", true),

        // ── Miscellaneous ─────────────────────────────────────────────────────

        new OracleConstruct("GREATEST",
            func("GREATEST"),
            "Oracle GREATEST() → PostgreSQL GREATEST() (compatible)", false),

        new OracleConstruct("LEAST",
            func("LEAST"),
            "Oracle LEAST() → PostgreSQL LEAST() (compatible)", false),

        new OracleConstruct("MERGE",
            Pattern.compile("\\bMERGE\\s+INTO\\b", Pattern.CASE_INSENSITIVE),
            "Oracle MERGE INTO ... USING ... ON ... WHEN MATCHED → PostgreSQL INSERT ... ON CONFLICT DO UPDATE", true),

        new OracleConstruct("SYS_GUID",
            func("SYS_GUID"),
            "Oracle SYS_GUID() → PostgreSQL gen_random_uuid()", true),

        new OracleConstruct("USER",
            // Case-sensitive: Oracle USER pseudocolumn is written ALL CAPS in SQL.
            // Using CASE_INSENSITIVE would match JPA entity names like "User" / "UserDto".
            Pattern.compile("\\bUSER\\s*(?:\\(\\)|(?=[,\\s;)]))"),
            "Oracle USER function → PostgreSQL CURRENT_USER", false),

        // ── Additional Oracle types / hints ───────────────────────────────────

        new OracleConstruct("VARCHAR2",
            word("VARCHAR2"),
            "Oracle VARCHAR2 type → PostgreSQL VARCHAR", false),

        new OracleConstruct("NUMBER_TYPE",
            Pattern.compile("\\bNUMBER\\s*\\(", Pattern.CASE_INSENSITIVE),
            "Oracle NUMBER(p,s) type → PostgreSQL NUMERIC(p,s)", false),

        new OracleConstruct("WMSYS_WM_CONCAT",
            Pattern.compile("\\bWMSYS\\.WM_CONCAT\\s*\\(", Pattern.CASE_INSENSITIVE),
            "Oracle WM_CONCAT (undocumented) → PostgreSQL STRING_AGG()", true),

        new OracleConstruct("OPTIMIZER_HINT",
            Pattern.compile("/\\*\\+"),
            "Oracle optimizer hint /*+ ... */ — silently ignored by PostgreSQL; remove or replace", false),

        new OracleConstruct("PRIOR",
            word("PRIOR"),
            "Oracle PRIOR keyword in hierarchical CONNECT BY queries", true)
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the names of all Oracle constructs found in the SQL string.
     */
    public static List<String> detect(String sql) {
        if (sql == null || sql.isBlank()) return List.of();
        List<String> found = new ArrayList<>();
        for (OracleConstruct c : CONSTRUCTS) {
            if (c.pattern().matcher(sql).find()) {
                found.add(c.name());
            }
        }
        return found;
    }

    /**
     * Returns full construct detail records for all matches.
     */
    public static List<OracleConstruct> detectWithDetails(String sql) {
        if (sql == null || sql.isBlank()) return List.of();
        List<OracleConstruct> found = new ArrayList<>();
        for (OracleConstruct c : CONSTRUCTS) {
            if (c.pattern().matcher(sql).find()) {
                found.add(c);
            }
        }
        return found;
    }

    /**
     * Returns true if at least one Oracle keyword (any severity) is found.
     * Used by ScanConfig.ORACLE_ONLY mode to decide whether to keep a query.
     */
    public static boolean hasAnyOracleKeyword(String sql) {
        if (sql == null || sql.isBlank()) return false;
        for (OracleConstruct c : CONSTRUCTS) {
            if (c.pattern().matcher(sql).find()) return true;
        }
        return false;
    }

    /**
     * Returns true if any critical construct is found (needs conversion work).
     */
    public static boolean requiresManualReview(String sql) {
        return detectWithDetails(sql).stream().anyMatch(OracleConstruct::criticalForConversion);
    }

    public static List<OracleConstruct> allConstructs() {
        return CONSTRUCTS;
    }

    // ── Pattern helpers ───────────────────────────────────────────────────────

    /** Matches a standalone keyword: \bKEYWORD\b */
    private static Pattern word(String keyword) {
        return Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE);
    }

    /** Matches a function call: \bFUNCTION\s*( */
    private static Pattern func(String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(", Pattern.CASE_INSENSITIVE);
    }
}
