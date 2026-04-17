package com.sqlparser.model;

/**
 * The detected content language of the query string — independent of how it was declared.
 *
 * QueryType captures the Java API call (createQuery vs createNativeQuery vs @Query).
 * QueryLanguage captures what the SQL string itself actually is.
 *
 * Example:
 *   createQuery("SELECT u FROM User u")           → QueryType.HQL,        QueryLanguage.HQL
 *   createQuery("SELECT * FROM users")            → QueryType.HQL,        QueryLanguage.NATIVE_SQL  ← mismatch!
 *   createNativeQuery("SELECT * FROM users")      → QueryType.NATIVE_SQL, QueryLanguage.NATIVE_SQL
 *   @Query("SELECT u FROM User u WHERE u.id = ?1")→ QueryType.ANNOTATION, QueryLanguage.HQL
 *   @Query(value="...", nativeQuery=true)         → QueryType.NATIVE_SQL, QueryLanguage.NATIVE_SQL
 */
public enum QueryLanguage {

    /** Pure HQL / JPQL — references entity class names and mapped properties. */
    HQL,

    /**
     * Native SQL — uses table names, Oracle constructs, or SQL-specific syntax.
     * Needs conversion when Oracle-specific constructs are present.
     */
    NATIVE_SQL,

    /**
     * Cannot be determined statically (e.g., unresolved variable,
     * no clear entity/table name cues in the SQL).
     */
    AMBIGUOUS
}
