package com.sqlparser.exporter;

import com.sqlparser.model.QueryInfo;
import com.sqlparser.model.QueryLanguage;
import com.sqlparser.registry.QueryRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlExporterSplitTest {

    // ── combinationKey ────────────────────────────────────────────────────────

    @Test
    void combination_key_format() {
        assertEquals("native_sql__native_sql",
                SqlExporter.combinationKey(QueryInfo.QueryType.NATIVE_SQL, QueryLanguage.NATIVE_SQL));
        assertEquals("hql__native_sql",
                SqlExporter.combinationKey(QueryInfo.QueryType.HQL, QueryLanguage.NATIVE_SQL));
        assertEquals("hql__hql",
                SqlExporter.combinationKey(QueryInfo.QueryType.HQL, QueryLanguage.HQL));
        assertEquals("annotation__ambiguous",
                SqlExporter.combinationKey(QueryInfo.QueryType.ANNOTATION, QueryLanguage.AMBIGUOUS));
    }

    // ── exportSplitFiles produces one file per combination ────────────────────

    @Test
    void creates_one_file_per_combination(@TempDir Path tmp) throws IOException {
        QueryRegistry registry = buildRegistry();
        SqlExporter exporter = new SqlExporter(registry);

        Map<String, Integer> summary = exporter.exportSplitFiles(tmp);

        // Three distinct combinations were registered
        assertEquals(3, summary.size());
        assertTrue(summary.containsKey("queries_native_sql__native_sql.sql"));
        assertTrue(summary.containsKey("queries_hql__hql.sql"));
        assertTrue(summary.containsKey("queries_hql__native_sql.sql"));
    }

    @Test
    void split_file_counts_are_correct(@TempDir Path tmp) throws IOException {
        QueryRegistry registry = buildRegistry();
        SqlExporter exporter = new SqlExporter(registry);

        Map<String, Integer> summary = exporter.exportSplitFiles(tmp);

        assertEquals(2, summary.get("queries_native_sql__native_sql.sql")); // Q0001, Q0002
        assertEquals(1, summary.get("queries_hql__hql.sql"));               // Q0003
        assertEquals(1, summary.get("queries_hql__native_sql.sql"));        // Q0004
    }

    @Test
    void split_files_contain_correct_qids(@TempDir Path tmp) throws IOException {
        QueryRegistry registry = buildRegistry();
        SqlExporter exporter = new SqlExporter(registry);
        exporter.exportSplitFiles(tmp);

        String nativeFile = Files.readString(tmp.resolve("queries_native_sql__native_sql.sql"));
        assertTrue(nativeFile.contains("BEGIN_QID:Q0001"));
        assertTrue(nativeFile.contains("BEGIN_QID:Q0002"));
        assertFalse(nativeFile.contains("BEGIN_QID:Q0003")); // HQL query must not appear here
        assertFalse(nativeFile.contains("BEGIN_QID:Q0004")); // mismatch must not appear here

        String hqlFile = Files.readString(tmp.resolve("queries_hql__hql.sql"));
        assertTrue(hqlFile.contains("BEGIN_QID:Q0003"));
        assertFalse(hqlFile.contains("BEGIN_QID:Q0001"));

        String mismatchFile = Files.readString(tmp.resolve("queries_hql__native_sql.sql"));
        assertTrue(mismatchFile.contains("BEGIN_QID:Q0004"));
    }

    @Test
    void split_files_contain_qid_markers(@TempDir Path tmp) throws IOException {
        QueryRegistry registry = buildRegistry();
        new SqlExporter(registry).exportSplitFiles(tmp);

        String content = Files.readString(tmp.resolve("queries_native_sql__native_sql.sql"));
        assertTrue(content.contains("-- BEGIN_QID:Q0001"));
        assertTrue(content.contains("-- END_QID:Q0001"));
    }

    @Test
    void no_file_produced_for_empty_combination(@TempDir Path tmp) throws IOException {
        QueryRegistry registry = buildRegistry();
        new SqlExporter(registry).exportSplitFiles(tmp);

        // annotation__native_sql was never registered → no file
        assertFalse(Files.exists(tmp.resolve("queries_annotation__native_sql.sql")));
    }

    // ── Replacement side: partial converted file only touches matching queries ─

    @Test
    void partial_converted_file_leaves_other_queries_untouched(@TempDir Path tmp) throws IOException {
        QueryRegistry registry = buildRegistry();
        SqlExporter exporter = new SqlExporter(registry);
        exporter.exportSplitFiles(tmp);

        // Simulate: only native_sql__native_sql was converted via ora2pg
        // Write a converted version of just that file
        Path convertedFile = tmp.resolve("converted_native_sql__native_sql.sql");
        String converted = """
                -- BEGIN_QID:Q0001 | file:A.java | method:m | line:1 | api:NATIVE_SQL | lang:NATIVE_SQL | hash:aaa
                SELECT * FROM users WHERE created_at > CURRENT_TIMESTAMP;
                -- END_QID:Q0001
                -- BEGIN_QID:Q0002 | file:B.java | method:m | line:1 | api:NATIVE_SQL | lang:NATIVE_SQL | hash:bbb
                SELECT id FROM orders LIMIT 100;
                -- END_QID:Q0002
                """;
        Files.writeString(convertedFile, converted);

        // Import conversions
        com.sqlparser.importer.SqlImporter importer = new com.sqlparser.importer.SqlImporter();
        Map<String, String> conversions = importer.importConversions(convertedFile);

        // Apply — only Q0001 and Q0002 get convertedSql set
        registry.applyConversions(conversions);

        // Q0001 and Q0002 have conversions; Q0003 and Q0004 do not
        assertTrue(registry.findById("Q0001").map(q -> q.getConvertedSql() != null).orElse(false));
        assertTrue(registry.findById("Q0002").map(q -> q.getConvertedSql() != null).orElse(false));
        assertNull(registry.findById("Q0003").map(QueryInfo::getConvertedSql).orElse(null),
                "HQL query Q0003 must not be touched");
        assertNull(registry.findById("Q0004").map(QueryInfo::getConvertedSql).orElse(null),
                "Mismatch query Q0004 must not be touched");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Builds a registry with four queries across three combinations:
     *   Q0001, Q0002 → NATIVE_SQL / NATIVE_SQL
     *   Q0003        → HQL / HQL
     *   Q0004        → HQL / NATIVE_SQL  (mismatch — createQuery with native SQL content)
     */
    private QueryRegistry buildRegistry() {
        QueryRegistry r = new QueryRegistry();

        QueryInfo q1 = r.register("A.java", "A", "m1", 10,
                "SELECT * FROM users WHERE created_at > SYSDATE",
                "SELECT * FROM users WHERE created_at > SYSDATE",
                QueryInfo.QueryType.NATIVE_SQL);
        q1.setQueryLanguage(QueryLanguage.NATIVE_SQL);

        QueryInfo q2 = r.register("B.java", "B", "m2", 20,
                "SELECT id FROM orders WHERE ROWNUM < 100",
                "SELECT id FROM orders WHERE ROWNUM < 100",
                QueryInfo.QueryType.NATIVE_SQL);
        q2.setQueryLanguage(QueryLanguage.NATIVE_SQL);

        QueryInfo q3 = r.register("C.java", "C", "m3", 30,
                "SELECT u FROM User u WHERE u.active = true",
                "SELECT u FROM User u WHERE u.active = true",
                QueryInfo.QueryType.HQL);
        q3.setQueryLanguage(QueryLanguage.HQL);

        QueryInfo q4 = r.register("D.java", "D", "m4", 40,
                "SELECT * FROM accounts WHERE NVL(status,'A') = 'A'",
                "SELECT * FROM accounts WHERE NVL(status,'A') = 'A'",
                QueryInfo.QueryType.HQL);   // wrong API — developer used createQuery with native SQL
        q4.setQueryLanguage(QueryLanguage.NATIVE_SQL);

        return r;
    }
}
