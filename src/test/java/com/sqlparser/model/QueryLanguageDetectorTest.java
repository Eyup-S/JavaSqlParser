package com.sqlparser.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryLanguageDetector — verifies HQL vs native SQL classification.
 */
class QueryLanguageDetectorTest {

    // ── createNativeQuery → always NATIVE_SQL ─────────────────────────────────

    @Test
    void native_query_api_type_always_returns_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT u FROM User u", QueryInfo.QueryType.NATIVE_SQL));
    }

    // ── HQL indicators ────────────────────────────────────────────────────────

    @Test
    void from_pascal_case_entity_is_hql() {
        assertEquals(QueryLanguage.HQL,
                QueryLanguageDetector.detect("SELECT u FROM User u WHERE u.active = true", QueryInfo.QueryType.HQL));
    }

    @Test
    void from_entity_with_package_is_hql() {
        assertEquals(QueryLanguage.HQL,
                QueryLanguageDetector.detect("FROM com.example.domain.Order o WHERE o.status = 'OPEN'", QueryInfo.QueryType.HQL));
    }

    @Test
    void join_fetch_is_hql() {
        assertEquals(QueryLanguage.HQL,
                QueryLanguageDetector.detect("SELECT u FROM User u JOIN FETCH u.roles WHERE u.active = true", QueryInfo.QueryType.HQL));
    }

    @Test
    void jpa_constructor_expression_is_hql() {
        assertEquals(QueryLanguage.HQL,
                QueryLanguageDetector.detect("SELECT NEW com.example.dto.UserDto(u.id, u.name) FROM User u", QueryInfo.QueryType.HQL));
    }

    @Test
    void camel_case_property_in_where_is_hql() {
        assertEquals(QueryLanguage.HQL,
                QueryLanguageDetector.detect("SELECT u FROM User u WHERE u.createdAt > :date", QueryInfo.QueryType.HQL));
    }

    // ── Native SQL indicators ─────────────────────────────────────────────────

    @Test
    void select_star_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT * FROM users WHERE active = 1", QueryInfo.QueryType.HQL));
    }

    @Test
    void snake_case_table_name_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT id FROM user_accounts WHERE status = 'A'", QueryInfo.QueryType.HQL));
    }

    @Test
    void all_caps_table_name_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT ID FROM USERS WHERE STATUS = 'A'", QueryInfo.QueryType.HQL));
    }

    @Test
    void oracle_sysdate_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT * FROM orders WHERE created_at > SYSDATE", QueryInfo.QueryType.HQL));
    }

    @Test
    void oracle_nvl_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT NVL(name, 'N/A') FROM users", QueryInfo.QueryType.HQL));
    }

    @Test
    void rownum_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT id FROM users WHERE ROWNUM < 10", QueryInfo.QueryType.HQL));
    }

    @Test
    void oracle_outer_join_syntax_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT a.id, b.name FROM a, b WHERE a.id = b.a_id(+)", QueryInfo.QueryType.HQL));
    }

    @Test
    void minus_set_operation_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT id FROM users MINUS SELECT id FROM deleted_users", QueryInfo.QueryType.HQL));
    }

    @Test
    void from_dual_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect("SELECT SYSDATE FROM DUAL", QueryInfo.QueryType.HQL));
    }

    // ── Mismatch detection (createQuery with native SQL) ─────────────────────

    @Test
    void detects_mismatch_createQuery_with_native_sql() {
        // This is what we want to catch: developer passed native SQL to createQuery()
        QueryLanguage lang = QueryLanguageDetector.detect(
                "SELECT * FROM users WHERE status = 'A'",
                QueryInfo.QueryType.HQL);
        assertEquals(QueryLanguage.NATIVE_SQL, lang,
                "createQuery() with native SQL content should be detected as NATIVE_SQL");
    }

    // ── Annotation type ───────────────────────────────────────────────────────

    @Test
    void annotation_with_hql_content_is_hql() {
        assertEquals(QueryLanguage.HQL,
                QueryLanguageDetector.detect(
                        "SELECT o FROM Order o WHERE o.customerId = :id",
                        QueryInfo.QueryType.ANNOTATION));
    }

    @Test
    void annotation_with_native_content_is_native_sql() {
        assertEquals(QueryLanguage.NATIVE_SQL,
                QueryLanguageDetector.detect(
                        "SELECT * FROM orders WHERE NVL(status, 'N') = 'A'",
                        QueryInfo.QueryType.ANNOTATION));
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void unresolved_marker_returns_ambiguous() {
        assertEquals(QueryLanguage.AMBIGUOUS,
                QueryLanguageDetector.detect("<<UNRESOLVED>>", QueryInfo.QueryType.HQL));
    }

    @Test
    void null_sql_returns_ambiguous() {
        assertEquals(QueryLanguage.AMBIGUOUS,
                QueryLanguageDetector.detect(null, QueryInfo.QueryType.HQL));
    }

    @Test
    void empty_sql_returns_ambiguous() {
        assertEquals(QueryLanguage.AMBIGUOUS,
                QueryLanguageDetector.detect("  ", QueryInfo.QueryType.HQL));
    }
}
