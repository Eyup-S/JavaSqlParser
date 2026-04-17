package com.sqlparser.extractor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqlStringResolver — covers all five resolution cases.
 */
class SqlStringResolverTest {

    private SqlStringResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SqlStringResolver();
    }

    // ── Case A: Direct string literal ─────────────────────────────────────────

    @Test
    void resolves_direct_string_literal() {
        String code = """
                class T {
                    void m() {
                        em.createQuery("SELECT u FROM User u WHERE u.active = true");
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT u FROM User u WHERE u.active = true", resolved);
    }

    // ── Case B: Variable reference ────────────────────────────────────────────

    @Test
    void resolves_variable_reference() {
        String code = """
                class T {
                    void m() {
                        String sql = "SELECT * FROM users";
                        em.createQuery(sql);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users", resolved);
    }

    @Test
    void resolves_variable_after_reassignment() {
        String code = """
                class T {
                    void m() {
                        String sql = "SELECT * FROM users";
                        sql = "SELECT id FROM accounts";
                        em.createQuery(sql);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT id FROM accounts", resolved);
    }

    // ── Case C: Binary concatenation ──────────────────────────────────────────

    @Test
    void resolves_inline_concatenation() {
        String code = """
                class T {
                    void m() {
                        em.createQuery("SELECT * FROM users " +
                                       "WHERE created_at > SYSDATE");
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users WHERE created_at > SYSDATE", resolved);
    }

    @Test
    void resolves_variable_concatenation() {
        String code = """
                class T {
                    void m() {
                        String sql = "SELECT * FROM users " +
                                     "WHERE status = 'A'";
                        em.createQuery(sql);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users WHERE status = 'A'", resolved);
    }

    // ── Case D: Incremental construction (+=) ────────────────────────────────

    @Test
    void resolves_incremental_construction() {
        String code = """
                class T {
                    void m() {
                        String sql = "SELECT * FROM users ";
                        sql += "WHERE status = 'A' ";
                        sql += "AND created_at > SYSDATE";
                        em.createQuery(sql);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users WHERE status = 'A' AND created_at > SYSDATE", resolved);
    }

    // ── Case E: Simple method call ────────────────────────────────────────────

    @Test
    void resolves_same_class_no_arg_method() {
        String code = """
                class T {
                    String buildQuery() {
                        return "SELECT * FROM users";
                    }
                    void m() {
                        em.createQuery(buildQuery());
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users", resolved);
    }

    // ── Unresolved / partial cases ────────────────────────────────────────────

    @Test
    void returns_unresolved_for_dynamic_expression() {
        String code = """
                class T {
                    void m(String filter) {
                        em.createQuery("SELECT * FROM users WHERE " + filter);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertTrue(resolved.contains(SqlStringResolver.UNRESOLVED_MARKER),
                "Expected UNRESOLVED_MARKER for dynamic parameter, got: " + resolved);
    }

    @Test
    void returns_partial_when_one_side_is_literal() {
        String code = """
                class T {
                    void m(String dynamicPart) {
                        em.createQuery("SELECT * FROM " + dynamicPart);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        // Should contain the literal part
        assertTrue(resolved.contains("SELECT * FROM "),
                "Expected partial resolution to retain literal part, got: " + resolved);
    }

    // ── Oracle construct awareness ────────────────────────────────────────────

    @Test
    void resolves_sql_with_oracle_sysdate() {
        String code = """
                class T {
                    void m() {
                        em.createNativeQuery("SELECT * FROM orders WHERE expire_date < SYSDATE AND ROWNUM < 100");
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertTrue(resolved.contains("SYSDATE"));
        assertTrue(resolved.contains("ROWNUM"));
    }

    // ── Multiline / text block ────────────────────────────────────────────────

    @Test
    void resolves_multiline_concatenation_three_parts() {
        String code = """
                class T {
                    void m() {
                        String sql = "SELECT u.id, u.name " +
                                     "FROM users u " +
                                     "WHERE u.active = 1";
                        em.createQuery(sql);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT u.id, u.name FROM users u WHERE u.active = 1", resolved);
    }

    @Test
    void resolves_mixed_init_and_concat_assign() {
        String code = """
                class T {
                    void m(boolean includeInactive) {
                        String sql = "SELECT * FROM users WHERE 1=1 ";
                        sql += "AND name IS NOT NULL";
                        em.createQuery(sql);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users WHERE 1=1 AND name IS NOT NULL", resolved);
    }

    // ── Case F: StringBuilder / StringBuffer ─────────────────────────────────

    @Test
    void resolves_stringbuilder_standalone_appends() {
        String code = """
                class T {
                    void m() {
                        StringBuilder queryBuilder = new StringBuilder();
                        queryBuilder.append("SELECT * FROM users ");
                        queryBuilder.append("WHERE status = 'A' ");
                        queryBuilder.append("AND created_at > SYSDATE");
                        em.createNativeQuery(queryBuilder.toString(), String.class);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users WHERE status = 'A' AND created_at > SYSDATE", resolved);
    }

    @Test
    void resolves_stringbuffer_standalone_appends() {
        String code = """
                class T {
                    void m() {
                        StringBuffer sb = new StringBuffer();
                        sb.append("SELECT id FROM orders ");
                        sb.append("WHERE ROWNUM < 100");
                        em.createNativeQuery(sb.toString());
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT id FROM orders WHERE ROWNUM < 100", resolved);
    }

    @Test
    void resolves_stringbuilder_with_constructor_initial_value() {
        String code = """
                class T {
                    void m() {
                        StringBuilder sb = new StringBuilder("SELECT * FROM users ");
                        sb.append("WHERE active = 1");
                        em.createNativeQuery(sb.toString());
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM users WHERE active = 1", resolved);
    }

    @Test
    void resolves_stringbuilder_chained_appends_in_one_statement() {
        String code = """
                class T {
                    void m() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("SELECT id, NVL(name, 'N/A') ").append("FROM users ").append("WHERE active = 1");
                        em.createNativeQuery(sb.toString());
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT id, NVL(name, 'N/A') FROM users WHERE active = 1", resolved);
    }

    @Test
    void resolves_inline_stringbuilder_chain_with_tostring() {
        String code = """
                class T {
                    void m() {
                        String sql = new StringBuilder()
                            .append("SELECT * FROM accounts ")
                            .append("WHERE balance > 0")
                            .toString();
                        em.createNativeQuery(sql);
                    }
                }
                """;
        String resolved = resolveFirstQueryArg(code);
        assertEquals("SELECT * FROM accounts WHERE balance > 0", resolved);
    }

    @Test
    void resolves_stringbuilder_with_conditional_marks_partial() {
        String code = """
                class T {
                    void m(boolean includeExpired) {
                        StringBuilder sb = new StringBuilder("SELECT * FROM orders WHERE 1=1 ");
                        if (includeExpired) {
                            sb.append("AND expire_date < SYSDATE ");
                        }
                        sb.append("ORDER BY id");
                        em.createNativeQuery(sb.toString());
                    }
                }
                """;
        // The conditional append is still collected (best-effort — we can't know branch outcome)
        String resolved = resolveFirstQueryArg(code);
        assertTrue(resolved.contains("SELECT * FROM orders"),
                "Should contain base SQL: " + resolved);
        assertTrue(resolved.contains("ORDER BY id"),
                "Should contain unconditional append: " + resolved);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveFirstQueryArg(String code) {
        CompilationUnit cu = StaticJavaParser.parse(code);

        // Find the call anywhere in the CU, then climb to its enclosing method.
        // (findFirst(MethodDeclaration) would return the first method in source order,
        //  which may be a builder helper that doesn't contain the query call.)
        MethodCallExpr call = cu.findFirst(MethodCallExpr.class,
                c -> c.getNameAsString().equals("createQuery") ||
                     c.getNameAsString().equals("createNativeQuery")).orElseThrow();

        MethodDeclaration method = call.findAncestor(MethodDeclaration.class).orElseThrow();
        Expression firstArg = call.getArguments().get(0);
        return resolver.resolve(firstArg, method);
    }
}
