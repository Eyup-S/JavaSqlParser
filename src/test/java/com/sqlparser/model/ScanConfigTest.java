package com.sqlparser.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ScanConfig — mode filtering and file exclusion.
 */
class ScanConfigTest {

    // ── File exclusion ────────────────────────────────────────────────────────

    @Test
    void excludes_files_matching_pattern() {
        ScanConfig config = ScanConfig.of(ScanConfig.ScanMode.ALL, ".*Test.*");
        assertTrue(config.shouldExclude("/src/main/java/com/example/UserRepositoryTest.java"));
        assertTrue(config.shouldExclude("/src/test/java/com/example/ServiceTest.java"));
    }

    @Test
    void does_not_exclude_non_matching_files() {
        ScanConfig config = ScanConfig.of(ScanConfig.ScanMode.ALL, ".*Test.*");
        assertFalse(config.shouldExclude("/src/main/java/com/example/UserRepository.java"));
        assertFalse(config.shouldExclude("/src/main/java/com/example/OrderService.java"));
    }

    @Test
    void no_exclusion_when_pattern_is_null() {
        ScanConfig config = ScanConfig.allQueries();
        assertFalse(config.shouldExclude("/src/main/java/com/example/Anything.java"));
    }

    @Test
    void supports_alternation_in_exclusion_pattern() {
        ScanConfig config = ScanConfig.of(ScanConfig.ScanMode.ALL, ".*(Test|Spec|IT)\\.java$");
        assertTrue(config.shouldExclude("/src/UserServiceIT.java"));
        assertTrue(config.shouldExclude("/src/OrderSpec.java"));
        assertTrue(config.shouldExclude("/src/RepoTest.java"));
        assertFalse(config.shouldExclude("/src/UserService.java"));
    }

    @Test
    void throws_for_invalid_regex() {
        assertThrows(IllegalArgumentException.class,
                () -> ScanConfig.of(ScanConfig.ScanMode.ALL, "[invalid"));
    }

    // ── Scan mode: ALL ────────────────────────────────────────────────────────

    @Test
    void all_mode_includes_sql_without_oracle_keywords() {
        ScanConfig config = ScanConfig.allQueries();
        assertTrue(config.shouldInclude("SELECT u FROM User u WHERE u.active = true"));
        assertTrue(config.shouldInclude("SELECT * FROM accounts"));
        assertTrue(config.shouldInclude("FROM Order o WHERE o.status = 'OPEN'"));
    }

    @Test
    void all_mode_also_includes_oracle_sql() {
        ScanConfig config = ScanConfig.allQueries();
        assertTrue(config.shouldInclude("SELECT * FROM users WHERE created_at > SYSDATE"));
    }

    // ── Scan mode: ORACLE_ONLY ────────────────────────────────────────────────

    @Test
    void oracle_only_includes_sql_with_sysdate() {
        ScanConfig config = ScanConfig.oracleOnly();
        assertTrue(config.shouldInclude("SELECT * FROM users WHERE created_at > SYSDATE"));
    }

    @Test
    void oracle_only_includes_sql_with_nvl() {
        ScanConfig config = ScanConfig.oracleOnly();
        assertTrue(config.shouldInclude("SELECT NVL(name, 'N/A') FROM users"));
    }

    @Test
    void oracle_only_includes_sql_with_rownum() {
        ScanConfig config = ScanConfig.oracleOnly();
        assertTrue(config.shouldInclude("SELECT id FROM orders WHERE ROWNUM < 100"));
    }

    @Test
    void oracle_only_includes_sql_with_connect_by() {
        ScanConfig config = ScanConfig.oracleOnly();
        assertTrue(config.shouldInclude("SELECT id FROM tree START WITH id = 1 CONNECT BY PRIOR parent_id = id"));
    }

    @Test
    void oracle_only_excludes_pure_hql() {
        ScanConfig config = ScanConfig.oracleOnly();
        assertFalse(config.shouldInclude("SELECT u FROM User u WHERE u.active = true"));
    }

    @Test
    void oracle_only_excludes_plain_native_sql_without_oracle_constructs() {
        ScanConfig config = ScanConfig.oracleOnly();
        assertFalse(config.shouldInclude("SELECT id, name FROM accounts WHERE status = 'A'"));
    }

    @Test
    void oracle_only_excludes_null_sql() {
        ScanConfig config = ScanConfig.oracleOnly();
        assertFalse(config.shouldInclude(null));
        assertFalse(config.shouldInclude(""));
    }

    @Test
    void oracle_only_mode_includes_each_keyword_from_spec() {
        ScanConfig config = ScanConfig.oracleOnly();

        String[] samples = {
            "SELECT SYSDATE FROM DUAL",
            "SELECT SYSTIMESTAMP FROM DUAL",
            "SELECT TRUNC(created_at) FROM orders",
            "SELECT ADD_MONTHS(d, 3) FROM t",
            "SELECT MONTHS_BETWEEN(d1, d2) FROM t",
            "SELECT LAST_DAY(d) FROM t",
            "SELECT NEXT_DAY(d, 'MONDAY') FROM t",
            "SELECT NVL(name, 'N/A') FROM t",
            "SELECT NVL2(x, 'yes', 'no') FROM t",
            "SELECT DECODE(status, 1, 'A', 'B') FROM t",
            "SELECT TO_DATE('2024-01-01', 'YYYY-MM-DD') FROM DUAL",
            "SELECT TO_CHAR(d, 'YYYY-MM-DD') FROM t",
            "SELECT TO_NUMBER(s) FROM t",
            "SELECT INSTR(name, 'x') FROM t",
            "SELECT SUBSTR(name, 1, 5) FROM t",
            "SELECT REGEXP_LIKE(name, '^A') FROM t",
            "SELECT REGEXP_SUBSTR(s, '[0-9]+') FROM t",
            "SELECT REGEXP_INSTR(s, 'x') FROM t",
            "SELECT REGEXP_REPLACE(s, 'a', 'b') FROM t",
            "SELECT LISTAGG(name, ',') WITHIN GROUP (ORDER BY name) FROM t",
            "SELECT LAG(value) OVER (ORDER BY dt) FROM t",
            "SELECT LEAD(value) OVER (ORDER BY dt) FROM t",
            "SELECT RANK() OVER (ORDER BY score) FROM t",
            "SELECT DENSE_RANK() OVER (ORDER BY score) FROM t",
            "SELECT ROW_NUMBER() OVER (ORDER BY id) FROM t",
            "SELECT id FROM t START WITH id = 1 CONNECT BY PRIOR parent_id = id",
            "SELECT LEVEL FROM t CONNECT BY LEVEL <= 5",
            "SELECT * FROM DUAL",
            "SELECT id FROM t WHERE ROWNUM < 10",
            "SELECT ROWID FROM t",
            "SELECT id FROM a MINUS SELECT id FROM b",
            "SELECT a.id, b.name FROM a, b WHERE a.id = b.a_id(+)",
            "INSERT INTO t VALUES (my_seq.NEXTVAL, 'x')",
            "SELECT GREATEST(a, b) FROM t",
            "SELECT LEAST(a, b) FROM t",
            "MERGE INTO target USING source ON (target.id = source.id) WHEN MATCHED THEN UPDATE SET name = source.name",
            "SELECT SYS_GUID() FROM DUAL"
        };

        for (String sql : samples) {
            assertTrue(config.shouldInclude(sql),
                    "ORACLE_ONLY mode should include SQL with Oracle keyword: " + sql);
        }
    }
}
