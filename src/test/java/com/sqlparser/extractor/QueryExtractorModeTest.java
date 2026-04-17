package com.sqlparser.extractor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.sqlparser.model.QueryInfo;
import com.sqlparser.model.QueryLanguage;
import com.sqlparser.model.ScanConfig;
import com.sqlparser.parser.ParserModule;
import com.sqlparser.registry.QueryRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for scan mode filtering and Hibernate API method coverage.
 */
class QueryExtractorModeTest {

    // ── ORACLE_ONLY mode ──────────────────────────────────────────────────────

    @Test
    void oracle_only_mode_skips_hql_without_oracle_constructs() {
        String code = """
                class Repo {
                    void m1() { em.createQuery("SELECT u FROM User u WHERE u.active = true"); }
                    void m2() { em.createQuery("SELECT * FROM orders WHERE created_at > SYSDATE"); }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.oracleOnly());
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).getResolvedSql().contains("SYSDATE"));
    }

    @Test
    void oracle_only_mode_skips_plain_native_sql() {
        String code = """
                class Repo {
                    void m1() { em.createNativeQuery("SELECT * FROM users WHERE active = 1"); }
                    void m2() { em.createNativeQuery("SELECT id FROM orders WHERE ROWNUM < 10"); }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.oracleOnly());
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).getResolvedSql().contains("ROWNUM"));
    }

    @Test
    void all_mode_includes_everything() {
        String code = """
                class Repo {
                    void m1() { em.createQuery("SELECT u FROM User u"); }
                    void m2() { em.createNativeQuery("SELECT * FROM users"); }
                    void m3() { em.createNativeQuery("SELECT SYSDATE FROM DUAL"); }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(3, queries.size());
    }

    // ── Language detection per method type ────────────────────────────────────

    @Test
    void createQuery_with_entity_syntax_detected_as_hql() {
        String code = """
                class Repo {
                    void m() { em.createQuery("SELECT u FROM User u WHERE u.createdAt > :date"); }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryLanguage.HQL, queries.get(0).getQueryLanguage());
    }

    @Test
    void createQuery_with_native_sql_detected_as_native_sql() {
        String code = """
                class Repo {
                    void m() { em.createQuery("SELECT * FROM users WHERE NVL(name,'N/A') = :n"); }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryLanguage.NATIVE_SQL, queries.get(0).getQueryLanguage());
        assertEquals(QueryInfo.QueryType.HQL, queries.get(0).getQueryType()); // API type is still HQL
    }

    @Test
    void createNativeQuery_always_native_sql_language() {
        String code = """
                class Repo {
                    void m() { em.createNativeQuery("SELECT u.id FROM User u WHERE u.active = true"); }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryLanguage.NATIVE_SQL, queries.get(0).getQueryLanguage());
    }

    // ── Hibernate 5.x deprecated methods ─────────────────────────────────────

    @Test
    void detects_hibernate5_createSQLQuery() {
        String code = """
                class Repo {
                    void m() {
                        session.createSQLQuery("SELECT * FROM legacy_table WHERE status = 'A'");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
    }

    // ── Hibernate 6.x new methods ─────────────────────────────────────────────

    @Test
    void detects_hibernate6_createNativeMutationQuery() {
        String code = """
                class Repo {
                    void m() {
                        session.createNativeMutationQuery(
                            "UPDATE orders SET status = 'CLOSED' WHERE expire_date < SYSDATE"
                        );
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
        assertTrue(queries.get(0).getOracleConstructs().contains("SYSDATE"));
    }

    @Test
    void detects_hibernate6_createNativeSelectionQuery() {
        String code = """
                class Repo {
                    void m() {
                        session.createNativeSelectionQuery(
                            "SELECT id, NVL(name, 'unknown') FROM users",
                            User.class
                        );
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
        assertTrue(queries.get(0).getOracleConstructs().contains("NVL"));
    }

    @Test
    void detects_hibernate6_createMutationQuery_hql() {
        String code = """
                class Repo {
                    void m() {
                        session.createMutationQuery("UPDATE User u SET u.active = false WHERE u.lastLogin < :cutoff");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.HQL, queries.get(0).getQueryType());
        assertEquals(QueryLanguage.HQL, queries.get(0).getQueryLanguage());
    }

    @Test
    void detects_hibernate6_createSelectionQuery() {
        String code = """
                class Repo {
                    void m() {
                        session.createSelectionQuery("SELECT u FROM User u WHERE u.active = true", User.class);
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.HQL, queries.get(0).getQueryType());
    }

    // ── Spring JdbcTemplate ───────────────────────────────────────────────────

    @Test
    void detects_jdbc_template_query() {
        String code = """
                class Dao {
                    void m() {
                        jdbcTemplate.queryForList(
                            "SELECT id, NVL(name, 'N/A') FROM users WHERE active = 1",
                            String.class
                        );
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
    }

    @Test
    void detects_jdbc_template_update() {
        String code = """
                class Dao {
                    void m() {
                        jdbcTemplate.update("UPDATE orders SET status = 'CLOSED' WHERE ROWNUM < 100");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).getOracleConstructs().contains("ROWNUM"));
    }

    @Test
    void jdbc_template_skips_non_sql_first_arg() {
        // update("tableName") — not SQL
        String code = """
                class Dao {
                    void m() {
                        jdbcTemplate.update("users");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(0, queries.size(), "Non-SQL first arg should not be registered");
    }

    // ── Oracle keyword language detection ─────────────────────────────────────

    @Test
    void oracle_only_mode_catches_all_user_specified_keywords() {
        ScanConfig oracleOnly = ScanConfig.oracleOnly();

        // Map of keyword → sample SQL containing it
        String[][] samples = {
            {"TRUNC",     "SELECT TRUNC(sale_date) FROM orders"},
            {"ADD_MONTHS","SELECT ADD_MONTHS(hire_date, 3) FROM emp"},
            {"DECODE",    "SELECT DECODE(status, 1, 'A', 'B') FROM t"},
            {"LISTAGG",   "SELECT LISTAGG(name, ',') WITHIN GROUP (ORDER BY name) FROM t"},
            {"CONNECT BY","SELECT id FROM t CONNECT BY PRIOR parent = id"},
            {"ROWNUM",    "SELECT id FROM t WHERE ROWNUM < 5"},
            {"MINUS",     "SELECT id FROM a MINUS SELECT id FROM b"},
            {"NEXTVAL",   "INSERT INTO t VALUES (my_seq.NEXTVAL)"},
            {"REGEXP_LIKE","SELECT * FROM t WHERE REGEXP_LIKE(name, '^A')"},
            {"SYS_GUID",  "INSERT INTO t VALUES (SYS_GUID(), 'x')"},
        };

        for (String[] s : samples) {
            assertTrue(oracleOnly.shouldInclude(s[1]),
                    "ORACLE_ONLY should include SQL containing " + s[0] + ": " + s[1]);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private List<QueryInfo> extractWith(String code, ScanConfig config) {
        QueryRegistry registry = new QueryRegistry();
        QueryExtractor extractor = new QueryExtractor(registry, config);
        CompilationUnit cu = StaticJavaParser.parse(code);
        ParserModule.ParsedFile pf = new ParserModule.ParsedFile(Path.of("Test.java"), cu);
        return extractor.extract(pf);
    }
}
