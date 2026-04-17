package com.sqlparser.rewriter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.sqlparser.model.QueryInfo;
import com.sqlparser.registry.QueryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CodeRewriter — verifies that SQL replacement is applied correctly
 * without breaking surrounding code structure.
 */
class CodeRewriterTest {

    private QueryRegistry registry;
    private CodeRewriter rewriter;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry();
        rewriter = new CodeRewriter(registry);
    }

    // ── Direct string literal replacement ────────────────────────────────────

    @Test
    void replaces_direct_string_literal(@TempDir Path tempDir) throws IOException {
        String original = """
                class Repo {
                    void findAll() {
                        em.createNativeQuery("SELECT * FROM users WHERE created_at > SYSDATE");
                    }
                }
                """;

        QueryInfo q = registry.register(
                tempDir.resolve("Repo.java").toString(),
                "Repo", "findAll", 3,
                "SELECT * FROM users WHERE created_at > SYSDATE",
                "SELECT * FROM users WHERE created_at > SYSDATE",
                QueryInfo.QueryType.NATIVE_SQL);
        q.setConvertedSql("SELECT * FROM users WHERE created_at > CURRENT_TIMESTAMP");

        Path javaFile = tempDir.resolve("Repo.java");
        Files.writeString(javaFile, original);

        int count = rewriter.rewriteFile(javaFile, List.of(q), null);

        assertEquals(1, count);
        String modified = Files.readString(javaFile);
        assertTrue(modified.contains("CURRENT_TIMESTAMP"), "Should contain converted SQL");
        assertFalse(modified.contains("SYSDATE"), "Should not contain original Oracle SQL");
    }

    // ── Variable reference replacement ────────────────────────────────────────

    @Test
    void replaces_variable_reference_with_inline_sql(@TempDir Path tempDir) throws IOException {
        String original = """
                class Repo {
                    void search() {
                        String sql = "SELECT id FROM dual WHERE ROWNUM < 10";
                        em.createNativeQuery(sql);
                    }
                }
                """;

        QueryInfo q = registry.register(
                tempDir.resolve("Repo.java").toString(),
                "Repo", "search", 4,
                "SELECT id FROM dual WHERE ROWNUM < 10",
                "SELECT id FROM dual WHERE ROWNUM < 10",
                QueryInfo.QueryType.NATIVE_SQL);
        q.setConvertedSql("SELECT id FROM (SELECT id FROM some_table LIMIT 10)");

        Path javaFile = tempDir.resolve("Repo.java");
        Files.writeString(javaFile, original);

        int count = rewriter.rewriteFile(javaFile, List.of(q), null);

        assertEquals(1, count);
        String modified = Files.readString(javaFile);
        assertTrue(modified.contains("LIMIT 10"), "Should contain converted SQL");
    }

    // ── Annotation replacement ────────────────────────────────────────────────

    @Test
    void replaces_at_query_annotation(@TempDir Path tempDir) throws IOException {
        String original = """
                interface UserRepository {
                    @Query(value = "SELECT * FROM users WHERE NVL(name, 'N/A') = :name", nativeQuery = true)
                    List findByName(String name);
                }
                """;

        QueryInfo q = registry.register(
                tempDir.resolve("UserRepository.java").toString(),
                "UserRepository", "findByName", 2,
                "SELECT * FROM users WHERE NVL(name, 'N/A') = :name",
                "SELECT * FROM users WHERE NVL(name, 'N/A') = :name",
                QueryInfo.QueryType.NATIVE_SQL);
        q.setConvertedSql("SELECT * FROM users WHERE COALESCE(name, 'N/A') = :name");

        Path javaFile = tempDir.resolve("UserRepository.java");
        Files.writeString(javaFile, original);

        int count = rewriter.rewriteFile(javaFile, List.of(q), null);

        assertEquals(1, count);
        String modified = Files.readString(javaFile);
        assertTrue(modified.contains("COALESCE"), "Should use COALESCE after conversion");
        assertFalse(modified.contains("NVL("), "Should not contain NVL after conversion");
    }

    // ── No-op when no conversion set ──────────────────────────────────────────

    @Test
    void does_not_rewrite_when_no_conversion_set(@TempDir Path tempDir) throws IOException {
        String original = """
                class Repo {
                    void find() {
                        em.createQuery("SELECT u FROM User u");
                    }
                }
                """;

        QueryInfo q = registry.register(
                tempDir.resolve("Repo.java").toString(),
                "Repo", "find", 3,
                "SELECT u FROM User u",
                "SELECT u FROM User u",
                QueryInfo.QueryType.HQL);
        // intentionally NOT setting convertedSql

        Path javaFile = tempDir.resolve("Repo.java");
        Files.writeString(javaFile, original);

        int count = rewriter.rewriteFile(javaFile, List.of(q), null);

        assertEquals(0, count);
        String content = Files.readString(javaFile);
        assertEquals(original, content, "File should be unchanged");
    }

    // ── Backup creation ───────────────────────────────────────────────────────

    @Test
    void creates_bak_backup_before_rewrite(@TempDir Path tempDir) throws IOException {
        String original = """
                class R {
                    void m() { em.createNativeQuery("SELECT SYSDATE FROM DUAL"); }
                }
                """;

        QueryInfo q = registry.register(
                tempDir.resolve("R.java").toString(),
                "R", "m", 2,
                "SELECT SYSDATE FROM DUAL",
                "SELECT SYSDATE FROM DUAL",
                QueryInfo.QueryType.NATIVE_SQL);
        q.setConvertedSql("SELECT CURRENT_TIMESTAMP");

        Path javaFile = tempDir.resolve("R.java");
        Files.writeString(javaFile, original);

        rewriter.rewriteFile(javaFile, List.of(q), null);

        Path backup = tempDir.resolve("R.java.bak");
        assertTrue(Files.exists(backup), "Backup .bak file should be created");
        assertEquals(original, Files.readString(backup), "Backup should contain original content");
    }

    // ── Special character escaping ────────────────────────────────────────────

    @Test
    void escapes_quotes_in_converted_sql(@TempDir Path tempDir) throws IOException {
        String original = """
                class R {
                    void m() { em.createNativeQuery("SELECT * FROM t WHERE status = 'A'"); }
                }
                """;

        QueryInfo q = registry.register(
                tempDir.resolve("R.java").toString(),
                "R", "m", 2,
                "SELECT * FROM t WHERE status = 'A'",
                "SELECT * FROM t WHERE status = 'A'",
                QueryInfo.QueryType.NATIVE_SQL);
        q.setConvertedSql("SELECT * FROM t WHERE status = 'ACTIVE'");

        Path javaFile = tempDir.resolve("R.java");
        Files.writeString(javaFile, original);

        int count = rewriter.rewriteFile(javaFile, List.of(q), null);

        assertEquals(1, count);
        // Verify the rewritten file compiles (basic syntax check via JavaParser)
        String modified = Files.readString(javaFile);
        assertDoesNotThrow(() -> StaticJavaParser.parse(modified), "Modified file should be valid Java");
    }
}
