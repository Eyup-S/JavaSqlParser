package com.sqlparser.extractor;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.sqlparser.model.QueryInfo;
import com.sqlparser.parser.ParserModule;
import com.sqlparser.registry.QueryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryExtractor — verifies detection of all Hibernate/JPA query creation points.
 */
class QueryExtractorTest {

    private QueryRegistry registry;
    private QueryExtractor extractor;

    @BeforeEach
    void setUp() {
        registry = new QueryRegistry();
        extractor = new QueryExtractor(registry);
    }

    // ── createQuery detection ─────────────────────────────────────────────────

    @Test
    void detects_entityManager_createQuery() {
        String code = """
                class UserRepo {
                    void findActive() {
                        entityManager.createQuery("SELECT u FROM User u WHERE u.active = true", User.class);
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "UserRepo.java");
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.HQL, queries.get(0).getQueryType());
        assertTrue(queries.get(0).getResolvedSql().contains("SELECT u FROM User u"));
    }

    @Test
    void detects_session_createQuery() {
        String code = """
                class OrderRepo {
                    void findPending() {
                        session.createQuery("FROM Order o WHERE o.status = 'PENDING'", Order.class);
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "OrderRepo.java");
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.HQL, queries.get(0).getQueryType());
    }

    // ── createNativeQuery / createSQLQuery detection ──────────────────────────

    @Test
    void detects_createNativeQuery() {
        String code = """
                class ProductRepo {
                    void searchProducts() {
                        entityManager.createNativeQuery("SELECT * FROM products WHERE price < 100", Product.class);
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "ProductRepo.java");
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
    }

    @Test
    void detects_deprecated_createSQLQuery() {
        String code = """
                class LegacyRepo {
                    void old() {
                        session.createSQLQuery("SELECT id FROM legacy_table");
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "LegacyRepo.java");
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
    }

    // ── @Query annotation detection ───────────────────────────────────────────

    @Test
    void detects_spring_data_query_annotation() {
        String code = """
                import org.springframework.data.jpa.repository.Query;
                interface UserRepository {
                    @Query("SELECT u FROM User u WHERE u.email = :email")
                    User findByEmail(String email);
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "UserRepository.java");
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.ANNOTATION, queries.get(0).getQueryType());
        assertTrue(queries.get(0).getResolvedSql().contains("SELECT u FROM User u"));
    }

    @Test
    void detects_native_query_annotation() {
        String code = """
                interface OrderRepository {
                    @Query(value = "SELECT * FROM orders WHERE status = :status", nativeQuery = true)
                    List findByStatus(String status);
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "OrderRepository.java");
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
    }

    @Test
    void detects_named_native_query_annotation() {
        String code = """
                @NamedNativeQuery(name = "User.findAll",
                    query = "SELECT * FROM users")
                class User {}
                """;
        List<QueryInfo> queries = extractFrom(code, "User.java");
        assertEquals(1, queries.size());
        assertEquals(QueryInfo.QueryType.NATIVE_SQL, queries.get(0).getQueryType());
        assertTrue(queries.get(0).getResolvedSql().contains("SELECT * FROM users"));
    }

    // ── Oracle construct flagging ─────────────────────────────────────────────

    @Test
    void flags_oracle_constructs_for_manual_review() {
        String code = """
                class OracleRepo {
                    void findRecent() {
                        em.createNativeQuery(
                            "SELECT * FROM orders WHERE created_at > SYSDATE - 7 AND ROWNUM < 50"
                        );
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "OracleRepo.java");
        assertEquals(1, queries.size());
        QueryInfo q = queries.get(0);
        assertTrue(q.isNeedsManualReview(), "Should be flagged for manual review");
        assertTrue(q.getOracleConstructs().contains("SYSDATE"));
        assertTrue(q.getOracleConstructs().contains("ROWNUM"));
    }

    @Test
    void flags_nvl_and_decode() {
        String code = """
                class OracleRepo {
                    void complex() {
                        em.createNativeQuery(
                            "SELECT NVL(name, 'N/A'), DECODE(status, 1, 'active', 'inactive') FROM users"
                        );
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "OracleRepo.java");
        QueryInfo q = queries.get(0);
        assertTrue(q.getOracleConstructs().contains("NVL"));
        assertTrue(q.getOracleConstructs().contains("DECODE"));
    }

    // ── Multiple queries in one file ──────────────────────────────────────────

    @Test
    void extracts_multiple_queries_from_one_file() {
        String code = """
                class MultiRepo {
                    void q1() { em.createQuery("SELECT u FROM User u"); }
                    void q2() { em.createNativeQuery("SELECT * FROM accounts"); }
                    void q3() { em.createQuery("FROM Product p WHERE p.active = true"); }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "MultiRepo.java");
        assertEquals(3, queries.size());
        // IDs should be sequential
        assertEquals("Q0001", queries.get(0).getId());
        assertEquals("Q0002", queries.get(1).getId());
        assertEquals("Q0003", queries.get(2).getId());
    }

    // ── Metadata accuracy ────────────────────────────────────────────────────

    @Test
    void records_correct_method_and_class_metadata() {
        String code = """
                class AccountService {
                    void findActiveAccounts() {
                        em.createQuery("SELECT a FROM Account a WHERE a.active = true");
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "AccountService.java");
        assertEquals(1, queries.size());
        QueryInfo q = queries.get(0);
        assertEquals("AccountService", q.getClassName());
        assertEquals("findActiveAccounts", q.getMethod());
        assertNotNull(q.getHash());
        assertFalse(q.getHash().isEmpty());
    }

    @Test
    void records_correct_line_number() {
        String code = """
                class Svc {
                    void m() {
                        String sql = "SELECT 1 FROM DUAL";
                        em.createNativeQuery(sql);
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "Svc.java");
        assertEquals(1, queries.size());
        // Line 4 is where createNativeQuery is called
        assertEquals(4, queries.get(0).getLine());
    }

    // ── Chained calls ─────────────────────────────────────────────────────────

    @Test
    void detects_chained_query_call() {
        String code = """
                class Repo {
                    void search() {
                        em.createQuery("SELECT u FROM User u ORDER BY u.name")
                          .setMaxResults(100)
                          .getResultList();
                    }
                }
                """;
        List<QueryInfo> queries = extractFrom(code, "Repo.java");
        assertEquals(1, queries.size());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private List<QueryInfo> extractFrom(String code, String filename) {
        // Fresh registry per call to avoid ID collision between tests
        QueryRegistry localRegistry = new QueryRegistry();
        QueryExtractor localExtractor = new QueryExtractor(localRegistry);

        CompilationUnit cu = StaticJavaParser.parse(code);
        ParserModule.ParsedFile pf = new ParserModule.ParsedFile(Path.of(filename), cu);
        return localExtractor.extract(pf);
    }
}
