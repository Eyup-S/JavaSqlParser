package com.sqlparser.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single extracted Hibernate/JPA query with full metadata for tracing and round-trip replacement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryInfo {

    public enum QueryType {
        HQL,           // createQuery() — JPQL/HQL
        NATIVE_SQL,    // createNativeQuery()
        ANNOTATION,    // @Query annotation
        JDBC           // prepareStatement / executeQuery — raw JDBC
    }

    private String id;
    private String file;
    private String className;
    private String method;
    private int line;
    private String originalSql;
    private String resolvedSql;
    private String hash;
    private QueryType queryType;
    /**
     * Detected language of the SQL content — independent of the Java API call type.
     * e.g., createQuery() with native SQL content → queryType=HQL, queryLanguage=NATIVE_SQL
     */
    private QueryLanguage queryLanguage = QueryLanguage.AMBIGUOUS;
    private boolean needsManualReview;
    private List<String> oracleConstructs = new ArrayList<>();
    private String convertedSql;
    private ResolutionStatus resolutionStatus = ResolutionStatus.RESOLVED;

    public enum ResolutionStatus {
        RESOLVED,
        PARTIAL,       // Some parts could not be resolved (e.g., runtime-computed fragments)
        UNRESOLVED     // Could not resolve — dynamic/runtime-only construction
    }

    public QueryInfo() {}

    public QueryInfo(String id, String file, String className, String method, int line,
                     String originalSql, String resolvedSql, String hash,
                     QueryType queryType) {
        this.id = id;
        this.file = file;
        this.className = className;
        this.method = method;
        this.line = line;
        this.originalSql = originalSql;
        this.resolvedSql = resolvedSql;
        this.hash = hash;
        this.queryType = queryType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public int getLine() { return line; }
    public void setLine(int line) { this.line = line; }

    public String getOriginalSql() { return originalSql; }
    public void setOriginalSql(String originalSql) { this.originalSql = originalSql; }

    public String getResolvedSql() { return resolvedSql; }
    public void setResolvedSql(String resolvedSql) { this.resolvedSql = resolvedSql; }

    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public QueryType getQueryType() { return queryType; }
    public void setQueryType(QueryType queryType) { this.queryType = queryType; }

    public QueryLanguage getQueryLanguage() { return queryLanguage; }
    public void setQueryLanguage(QueryLanguage queryLanguage) { this.queryLanguage = queryLanguage; }

    public boolean isNeedsManualReview() { return needsManualReview; }
    public void setNeedsManualReview(boolean needsManualReview) { this.needsManualReview = needsManualReview; }

    public List<String> getOracleConstructs() { return oracleConstructs; }
    public void setOracleConstructs(List<String> oracleConstructs) { this.oracleConstructs = oracleConstructs; }

    public String getConvertedSql() { return convertedSql; }
    public void setConvertedSql(String convertedSql) { this.convertedSql = convertedSql; }

    public ResolutionStatus getResolutionStatus() { return resolutionStatus; }
    public void setResolutionStatus(ResolutionStatus resolutionStatus) { this.resolutionStatus = resolutionStatus; }

    @Override
    public String toString() {
        return String.format("QueryInfo{id='%s', file='%s', method='%s', line=%d, type=%s, lang=%s, review=%s}",
                id, file, method, line, queryType, queryLanguage, needsManualReview);
    }
}
