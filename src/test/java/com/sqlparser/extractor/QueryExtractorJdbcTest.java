package com.sqlparser.extractor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.sqlparser.model.QueryInfo;
import com.sqlparser.model.ScanConfig;
import com.sqlparser.parser.ParserModule;
import com.sqlparser.registry.QueryRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for raw JDBC extraction (prepareStatement, prepareCall, executeQuery, executeUpdate)
 * and SourceMode gating (ALL / HIBERNATE_ONLY / JDBC_ONLY).
 */
class QueryExtractorJdbcTest {

    // ── prepareStatement ──────────────────────────────────────────────────────

    @Test
    void detects_prepareStatement() {
        String code = """
                class Dao {
                    void find() {
                        PreparedStatement ps = conn.prepareStatement(
                            "SELECT id, name FROM users WHERE active = 1"
                        );
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.JDBC, queries.get(0).getQueryType());
        assertTrue(queries.get(0).getResolvedSql().contains("SELECT id, name FROM users"));
    }

    @Test
    void detects_prepareStatement_with_oracle_constructs() {
        String code = """
                class Dao {
                    void find() {
                        conn.prepareStatement(
                            "SELECT NVL(name, 'N/A') FROM users WHERE ROWNUM < 100"
                        );
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        QueryInfo q = queries.get(0);
        assertEquals(QueryInfo.QueryType.JDBC, q.getQueryType());
        assertTrue(q.getOracleConstructs().contains("NVL"));
        assertTrue(q.getOracleConstructs().contains("ROWNUM"));
        assertTrue(q.isNeedsManualReview());
    }

    // ── executeQuery ──────────────────────────────────────────────────────────

    @Test
    void detects_executeQuery_with_sql_arg() {
        String code = """
                class Dao {
                    void run() {
                        ResultSet rs = stmt.executeQuery("SELECT * FROM orders WHERE status = 'OPEN'");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.JDBC, queries.get(0).getQueryType());
    }

    @Test
    void skips_executeQuery_with_no_args() {
        // PreparedStatement.executeQuery() — no SQL arg, must be skipped
        String code = """
                class Dao {
                    void run() {
                        PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM DUAL");
                        ResultSet rs = ps.executeQuery();
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        // Only the prepareStatement call should be detected, not the no-arg executeQuery
        assertEquals(1, queries.size());
        assertTrue(queries.get(0).getResolvedSql().contains("SELECT 1"));
    }

    // ── executeUpdate ─────────────────────────────────────────────────────────

    @Test
    void detects_executeUpdate_with_sql_arg() {
        String code = """
                class Dao {
                    void update() {
                        stmt.executeUpdate("UPDATE orders SET status = 'CLOSED' WHERE expire_date < SYSDATE");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.JDBC, queries.get(0).getQueryType());
        assertTrue(queries.get(0).getOracleConstructs().contains("SYSDATE"));
    }

    // ── prepareCall ───────────────────────────────────────────────────────────

    @Test
    void detects_prepareCall_with_call_syntax() {
        String code = """
                class Dao {
                    void callProc() {
                        CallableStatement cs = conn.prepareCall("CALL calculate_bonus(?, ?)");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.JDBC, queries.get(0).getQueryType());
    }

    // ── looksLikeSql guard ────────────────────────────────────────────────────

    @Test
    void skips_prepareStatement_with_non_sql_arg() {
        // A string that doesn't start with a SQL keyword should not be registered
        String code = """
                class Dao {
                    void m() {
                        conn.prepareStatement("users_table");
                    }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(0, queries.size());
    }

    // ── SourceMode.HIBERNATE_ONLY ─────────────────────────────────────────────

    @Test
    void hibernate_only_skips_prepareStatement() {
        String code = """
                class Dao {
                    void q1() { em.createNativeQuery("SELECT * FROM users"); }
                    void q2() { conn.prepareStatement("SELECT * FROM orders"); }
                }
                """;
        ScanConfig config = ScanConfig.of(ScanConfig.ScanMode.ALL, ScanConfig.SourceMode.HIBERNATE_ONLY, null, false);
        List<QueryInfo> queries = extractWith(code, config);
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
    }

    @Test
    void hibernate_only_skips_executeQuery() {
        String code = """
                class Dao {
                    void q1() { em.createQuery("SELECT u FROM User u"); }
                    void q2() { stmt.executeQuery("SELECT * FROM accounts"); }
                }
                """;
        ScanConfig config = ScanConfig.of(ScanConfig.ScanMode.ALL, ScanConfig.SourceMode.HIBERNATE_ONLY, null, false);
        List<QueryInfo> queries = extractWith(code, config);
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.HQL, queries.get(0).getQueryType());
    }

    // ── SourceMode.JDBC_ONLY ──────────────────────────────────────────────────

    @Test
    void jdbc_only_skips_createNativeQuery() {
        String code = """
                class Dao {
                    void q1() { em.createNativeQuery("SELECT * FROM users"); }
                    void q2() { conn.prepareStatement("SELECT * FROM orders"); }
                }
                """;
        ScanConfig config = ScanConfig.of(ScanConfig.ScanMode.ALL, ScanConfig.SourceMode.JDBC_ONLY, null, false);
        List<QueryInfo> queries = extractWith(code, config);
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.JDBC, queries.get(0).getQueryType());
    }

    @Test
    void jdbc_only_skips_query_annotations() {
        String code = """
                interface UserRepo {
                    @Query("SELECT u FROM User u")
                    List<User> findAll();
                }
                class Dao {
                    void q() { conn.prepareStatement("SELECT * FROM accounts WHERE active = 1"); }
                }
                """;
        ScanConfig config = ScanConfig.of(ScanConfig.ScanMode.ALL, ScanConfig.SourceMode.JDBC_ONLY, null, false);
        List<QueryInfo> queries = extractWith(code, config);
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.JDBC, queries.get(0).getQueryType());
    }

    // ── SourceMode.ALL ────────────────────────────────────────────────────────

    @Test
    void source_mode_all_picks_up_both_hibernate_and_jdbc() {
        String code = """
                class Dao {
                    void q1() { em.createNativeQuery("SELECT * FROM products"); }
                    void q2() { conn.prepareStatement("SELECT * FROM orders WHERE status = 'OPEN'"); }
                    void q3() { stmt.executeUpdate("UPDATE accounts SET balance = 0"); }
                }
                """;
        List<QueryInfo> queries = extractWith(code, ScanConfig.allQueries());
        assertEquals(3, queries.size());
        long jdbcCount = queries.stream()
                .filter(q -> q.getQueryType() == QueryInfo.QueryType.JDBC)
                .count();
        long hibernateCount = queries.stream()
                .filter(q -> q.getQueryType() == QueryInfo.QueryType.NATIVE_SQL)
                .count();
        assertEquals(2, jdbcCount);
        assertEquals(1, hibernateCount);
    }

    // ── No duplicates for same location ──────────────────────────────────────

    @Test
    void does_not_register_same_location_twice() {
        // If somehow the visitor visits the same call twice (e.g., parent class traversal),
        // the location guard must prevent duplicate registration.
        String code = """
                class Dao {
                    void m() {
                        conn.prepareStatement("SELECT * FROM users");
                    }
                }
                """;
        QueryRegistry registry = new QueryRegistry();
        QueryExtractor extractor = new QueryExtractor(registry, ScanConfig.allQueries());
        CompilationUnit cu = StaticJavaParser.parse(code);
        ParserModule.ParsedFile pf = new ParserModule.ParsedFile(Path.of("Dao.java"), cu);

        // Extract twice — second pass should add nothing
        extractor.extract(pf);
        extractor.extract(pf);

        assertEquals(1, registry.size());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private List<QueryInfo> extractWith(String code, ScanConfig config) {
        QueryRegistry registry = new QueryRegistry();
        QueryExtractor extractor = new QueryExtractor(registry, config);
        CompilationUnit cu = StaticJavaParser.parse(code);
        ParserModule.ParsedFile pf = new ParserModule.ParsedFile(Path.of("Dao.java"), cu);
        return extractor.extract(pf);
    }
}
