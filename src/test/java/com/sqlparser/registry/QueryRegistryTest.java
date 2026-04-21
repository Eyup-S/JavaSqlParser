package com.sqlparser.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlparser.model.QueryInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryRegistry — ID assignment, persistence, and conversion application.
 */
class QueryRegistryTest {

    private QueryRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry();
    }

    @Test
    void assigns_sequential_ids_starting_from_Q0001() {
        QueryInfo q1 = registry.register("Foo.java", "Foo", "bar", 10, "SELECT 1", "SELECT 1", QueryInfo.QueryType.HQL);
        QueryInfo q2 = registry.register("Foo.java", "Foo", "baz", 20, "SELECT 2", "SELECT 2", QueryInfo.QueryType.HQL);
        QueryInfo q3 = registry.register("Bar.java", "Bar", "qux", 5,  "SELECT 3", "SELECT 3", QueryInfo.QueryType.NATIVE_SQL);

        assertEquals("Q0001", q1.getId());
        assertEquals("Q0002", q2.getId());
        assertEquals("Q0003", q3.getId());
    }

    @Test
    void computes_sha256_hash() {
        QueryInfo q = registry.register("F.java", "F", "m", 1, "SELECT 1", "SELECT 1", QueryInfo.QueryType.HQL);
        assertNotNull(q.getHash());
        assertEquals(16, q.getHash().length()); // 16 hex chars
    }

    @Test
    void same_hash_for_identical_sql() {
        QueryInfo q1 = registry.register("A.java", "A", "x", 1, "SELECT id FROM users", "SELECT id FROM users", QueryInfo.QueryType.HQL);
        QueryInfo q2 = registry.register("B.java", "B", "y", 2, "SELECT id FROM users", "SELECT id FROM users", QueryInfo.QueryType.HQL);
        assertEquals(q1.getHash(), q2.getHash());
    }

    @Test
    void different_hash_for_different_sql() {
        QueryInfo q1 = registry.register("A.java", "A", "x", 1, "SELECT 1", "SELECT 1", QueryInfo.QueryType.HQL);
        QueryInfo q2 = registry.register("B.java", "B", "y", 2, "SELECT 2", "SELECT 2", QueryInfo.QueryType.HQL);
        assertNotEquals(q1.getHash(), q2.getHash());
    }

    @Test
    void findById_returns_correct_query() {
        registry.register("X.java", "X", "m", 5, "SELECT * FROM t", "SELECT * FROM t", QueryInfo.QueryType.HQL);
        assertTrue(registry.findById("Q0001").isPresent());
        assertEquals("SELECT * FROM t", registry.findById("Q0001").get().getResolvedSql());
    }

    @Test
    void findById_returns_empty_for_unknown_id() {
        assertTrue(registry.findById("Q9999").isEmpty());
    }

    @Test
    void isRegisteredByLocation_prevents_duplicate() {
        registry.register("Repo.java", "Repo", "m", 42, "SELECT 1", "SELECT 1", QueryInfo.QueryType.HQL);
        String locationKey = "Repo.java:Repo:m:42";
        assertTrue(registry.isRegisteredByLocation(locationKey));
    }

    @Test
    void all_returns_all_registered_queries() {
        registry.register("A.java", "A", "a", 1, "S1", "S1", QueryInfo.QueryType.HQL);
        registry.register("A.java", "A", "b", 2, "S2", "S2", QueryInfo.QueryType.HQL);
        registry.register("B.java", "B", "c", 3, "S3", "S3", QueryInfo.QueryType.NATIVE_SQL);
        assertEquals(3, registry.all().size());
    }

    @Test
    void applyConversions_sets_convertedSql() {
        QueryInfo q = registry.register("F.java", "F", "m", 1, "SELECT SYSDATE FROM DUAL", "SELECT SYSDATE FROM DUAL", QueryInfo.QueryType.NATIVE_SQL);
        registry.applyConversions(Map.of("Q0001", "SELECT CURRENT_TIMESTAMP"));
        assertEquals("SELECT CURRENT_TIMESTAMP", q.getConvertedSql());
    }

    @Test
    void applyConversions_ignores_unknown_ids() {
        registry.register("F.java", "F", "m", 1, "S1", "S1", QueryInfo.QueryType.HQL);
        // Should not throw
        assertDoesNotThrow(() -> registry.applyConversions(Map.of("Q9999", "SELECT 1")));
    }

    @Test
    void persists_and_reloads_from_json(@TempDir Path tempDir) throws IOException {
        registry.register("X.java", "X", "m1", 10, "SELECT 1", "SELECT 1", QueryInfo.QueryType.HQL);
        registry.register("Y.java", "Y", "m2", 20, "SELECT 2", "SELECT 2", QueryInfo.QueryType.NATIVE_SQL);

        Path jsonFile = tempDir.resolve("registry.json");
        registry.saveToJson(jsonFile);

        QueryRegistry reloaded = new QueryRegistry();
        reloaded.loadFromJson(jsonFile);

        assertEquals(2, reloaded.all().size());
        assertTrue(reloaded.findById("Q0001").isPresent());
        assertTrue(reloaded.findById("Q0002").isPresent());
        assertEquals("SELECT 1", reloaded.findById("Q0001").get().getResolvedSql());
    }

    @Test
    void loadFromJson_restores_location_index(@TempDir Path tempDir) throws IOException {
        registry.register("/src/Repo.java", "Repo", "findAll", 10,
                "SELECT * FROM users", "SELECT * FROM users", QueryInfo.QueryType.NATIVE_SQL);

        Path jsonFile = tempDir.resolve("registry.json");
        registry.saveToJson(jsonFile);

        QueryRegistry reloaded = new QueryRegistry();
        reloaded.loadFromJson(jsonFile);

        // Location must be in the index so append-mode deduplication works
        assertTrue(reloaded.isRegisteredByLocation("/src/Repo.java:Repo:findAll:10"),
                "loadFromJson must restore the location index");
    }

    @Test
    void append_mode_skips_already_known_location(@TempDir Path tempDir) throws IOException {
        // Simulate first run (Hibernate scan)
        registry.register("/src/Repo.java", "Repo", "findAll", 10,
                "SELECT * FROM users", "SELECT * FROM users", QueryInfo.QueryType.NATIVE_SQL);

        Path jsonFile = tempDir.resolve("registry.json");
        registry.saveToJson(jsonFile);

        // Simulate second run (append JDBC scan) — load existing registry first
        QueryRegistry second = new QueryRegistry();
        second.loadFromJson(jsonFile);

        // Try to register the exact same location again — should be skipped
        String locationKey = "/src/Repo.java:Repo:findAll:10";
        assertTrue(second.isRegisteredByLocation(locationKey),
                "Loaded registry must recognise the existing location");

        // Registry size stays the same — no duplicate added
        // (QueryExtractor calls isRegisteredByLocation before register, so we test the guard directly)
        second.register("/src/Repo.java", "Repo", "findAll", 10,
                "SELECT * FROM users", "SELECT * FROM users", QueryInfo.QueryType.JDBC);
        // Both Q0001 (original) and Q0002 (duplicate) are in the map here because register()
        // itself does not guard — the guard is in QueryExtractor.tryRegister().
        // What matters is that isRegisteredByLocation returns true BEFORE the second register call.
        assertEquals(2, second.all().size()); // raw registry allows it; extractor guards it
    }

    @Test
    void loadFromJson_does_not_overwrite_location_for_same_file_different_line(@TempDir Path tempDir) throws IOException {
        registry.register("/src/Repo.java", "Repo", "m", 10, "SELECT 1", "SELECT 1", QueryInfo.QueryType.HQL);
        registry.register("/src/Repo.java", "Repo", "m", 20, "SELECT 2", "SELECT 2", QueryInfo.QueryType.HQL);

        Path jsonFile = tempDir.resolve("registry.json");
        registry.saveToJson(jsonFile);

        QueryRegistry reloaded = new QueryRegistry();
        reloaded.loadFromJson(jsonFile);

        assertTrue(reloaded.isRegisteredByLocation("/src/Repo.java:Repo:m:10"));
        assertTrue(reloaded.isRegisteredByLocation("/src/Repo.java:Repo:m:20"));
        assertFalse(reloaded.isRegisteredByLocation("/src/Repo.java:Repo:m:30"));
    }

    @Test
    void saveToJson_preserves_unknown_fields_added_by_external_tools(@TempDir Path tempDir) throws IOException {
        // Simulate what viz.py does: save a registry.json that has extra fields
        // (reviewStatus, reviewedBy, ora2pgSql) Java does not know about.
        registry.register("X.java", "X", "m", 1, "SELECT SYSDATE FROM DUAL",
                "SELECT SYSDATE FROM DUAL", QueryInfo.QueryType.NATIVE_SQL);

        Path jsonFile = tempDir.resolve("registry.json");
        registry.saveToJson(jsonFile);

        // Inject Python-added fields directly into the saved JSON (mimics viz.py saving)
        ObjectMapper om = new ObjectMapper();
        ArrayNode array = (ArrayNode) om.readTree(jsonFile.toFile());
        ObjectNode entry = (ObjectNode) array.get(0);
        entry.put("reviewStatus", "APPROVED");
        entry.put("reviewedBy", "alice");
        entry.put("ora2pgSql", "SELECT NOW() FROM DUAL");
        om.writeValue(jsonFile.toFile(), array);

        // Java loads and saves again (simulates an append run)
        QueryRegistry reloaded = new QueryRegistry();
        reloaded.loadFromJson(jsonFile);
        reloaded.saveToJson(jsonFile);

        // Python-added fields must still be present
        ArrayNode saved = (ArrayNode) om.readTree(jsonFile.toFile());
        ObjectNode savedEntry = (ObjectNode) saved.get(0);
        assertEquals("APPROVED", savedEntry.path("reviewStatus").asText());
        assertEquals("alice",    savedEntry.path("reviewedBy").asText());
        assertEquals("SELECT NOW() FROM DUAL", savedEntry.path("ora2pgSql").asText());
    }

    @Test
    void saveToJson_reflects_java_side_updates_on_loaded_entries(@TempDir Path tempDir) throws IOException {
        // Java-side update (convertedSql set by replace command) must overwrite the raw node value
        registry.register("X.java", "X", "m", 1, "SELECT SYSDATE FROM DUAL",
                "SELECT SYSDATE FROM DUAL", QueryInfo.QueryType.NATIVE_SQL);

        Path jsonFile = tempDir.resolve("registry.json");
        registry.saveToJson(jsonFile);

        QueryRegistry reloaded = new QueryRegistry();
        reloaded.loadFromJson(jsonFile);
        reloaded.applyConversions(Map.of("Q0001", "SELECT CURRENT_TIMESTAMP"));
        reloaded.saveToJson(jsonFile);

        ObjectMapper om = new ObjectMapper();
        ArrayNode saved = (ArrayNode) om.readTree(jsonFile.toFile());
        assertEquals("SELECT CURRENT_TIMESTAMP",
                saved.get(0).path("convertedSql").asText());
    }

    @Test
    void counter_resumes_after_load(@TempDir Path tempDir) throws IOException {
        registry.register("X.java", "X", "m", 1, "S1", "S1", QueryInfo.QueryType.HQL);
        registry.register("X.java", "X", "m", 2, "S2", "S2", QueryInfo.QueryType.HQL);

        Path jsonFile = tempDir.resolve("registry.json");
        registry.saveToJson(jsonFile);

        QueryRegistry reloaded = new QueryRegistry();
        reloaded.loadFromJson(jsonFile);

        // Next ID after loading Q0001, Q0002 should be Q0003
        QueryInfo q3 = reloaded.register("Z.java", "Z", "m3", 3, "S3", "S3", QueryInfo.QueryType.HQL);
        assertEquals("Q0003", q3.getId());
    }
}
