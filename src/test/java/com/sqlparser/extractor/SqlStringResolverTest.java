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
