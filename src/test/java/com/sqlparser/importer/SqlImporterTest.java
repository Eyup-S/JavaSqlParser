package com.sqlparser.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SqlImporter — verifies parsing of converted SQL files with QID markers.
 */
class SqlImporterTest {

    private final SqlImporter importer = new SqlImporter();

    @Test
    void imports_single_query_block(@TempDir Path tempDir) throws IOException {
        String content = """
                -- BEGIN_QID:Q0001 | file:Repo.java | method:find | line:10 | type:NATIVE_SQL | hash:abc123
                SELECT * FROM users WHERE created_at > CURRENT_TIMESTAMP;
                -- END_QID:Q0001
                """;

        Path file = tempDir.resolve("converted.sql");
        Files.writeString(file, content);

        Map<String, String> result = importer.importConversions(file);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("Q0001"));
        assertEquals("SELECT * FROM users WHERE created_at > CURRENT_TIMESTAMP", result.get("Q0001"));
    }

    @Test
    void imports_multiple_query_blocks(@TempDir Path tempDir) throws IOException {
        String content = """
                -- BEGIN_QID:Q0001 | file:A.java | method:m1 | line:5 | type:HQL | hash:aaa
                SELECT u FROM User u WHERE u.active = true
                -- END_QID:Q0001

                -- BEGIN_QID:Q0002 | file:B.java | method:m2 | line:15 | type:NATIVE_SQL | hash:bbb
                SELECT * FROM accounts WHERE balance > 0
                -- END_QID:Q0002
                """;

        Path file = tempDir.resolve("converted.sql");
        Files.writeString(file, content);

        Map<String, String> result = importer.importConversions(file);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("Q0001"));
        assertTrue(result.containsKey("Q0002"));
        assertEquals("SELECT u FROM User u WHERE u.active = true", result.get("Q0001"));
        assertEquals("SELECT * FROM accounts WHERE balance > 0", result.get("Q0002"));
    }

    @Test
    void strips_trailing_semicolon(@TempDir Path tempDir) throws IOException {
        String content = """
                -- BEGIN_QID:Q0003 | file:X.java | method:x | line:1 | type:NATIVE_SQL | hash:xxx
                SELECT id FROM products;
                -- END_QID:Q0003
                """;

        Path file = tempDir.resolve("converted.sql");
        Files.writeString(file, content);

        Map<String, String> result = importer.importConversions(file);

        assertFalse(result.get("Q0003").endsWith(";"), "Should strip trailing semicolon");
        assertEquals("SELECT id FROM products", result.get("Q0003"));
    }

    @Test
    void skips_review_required_comments(@TempDir Path tempDir) throws IOException {
        String content = """
                -- BEGIN_QID:Q0004 | file:X.java | method:x | line:1 | type:NATIVE_SQL | hash:xxx
                -- REVIEW_REQUIRED: SYSDATE, ROWNUM
                -- WARNING: PARTIAL — some fragments could not be statically resolved
                SELECT * FROM t LIMIT 10
                -- END_QID:Q0004
                """;

        Path file = tempDir.resolve("converted.sql");
        Files.writeString(file, content);

        Map<String, String> result = importer.importConversions(file);

        assertEquals("SELECT * FROM t LIMIT 10", result.get("Q0004"));
    }

    @Test
    void handles_multiline_sql(@TempDir Path tempDir) throws IOException {
        String content = """
                -- BEGIN_QID:Q0005 | file:X.java | method:x | line:1 | type:NATIVE_SQL | hash:xxx
                SELECT u.id,
                       u.name,
                       u.email
                FROM users u
                WHERE u.active = true
                -- END_QID:Q0005
                """;

        Path file = tempDir.resolve("converted.sql");
        Files.writeString(file, content);

        Map<String, String> result = importer.importConversions(file);

        assertTrue(result.get("Q0005").contains("SELECT u.id"));
        assertTrue(result.get("Q0005").contains("FROM users u"));
        assertTrue(result.get("Q0005").contains("WHERE u.active = true"));
    }

    @Test
    void returns_empty_map_for_file_without_markers(@TempDir Path tempDir) throws IOException {
        String content = "SELECT * FROM users;\nSELECT id FROM accounts;\n";

        Path file = tempDir.resolve("nomarkers.sql");
        Files.writeString(file, content);

        Map<String, String> result = importer.importConversions(file);

        assertTrue(result.isEmpty());
    }

    @Test
    void handles_case_insensitive_markers(@TempDir Path tempDir) throws IOException {
        String content = """
                -- begin_qid:Q0006 | file:X.java | method:x | line:1 | type:HQL | hash:fff
                SELECT 1
                -- end_qid:Q0006
                """;

        Path file = tempDir.resolve("lower.sql");
        Files.writeString(file, content);

        Map<String, String> result = importer.importConversions(file);

        assertTrue(result.containsKey("Q0006"));
    }
}
