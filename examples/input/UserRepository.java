package com.example.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.util.List;

/**
 * Example repository demonstrating all five SQL extraction cases.
 * This file is used as input for the JavaSqlParser tool.
 */
@Repository
public class UserRepository {

    @PersistenceContext
    private EntityManager em;

    // ── Case A: Direct string literal ──────────────────────────────────────────

    public List<Object[]> findAllActive() {
        return em.createNativeQuery(
            "SELECT id, name, email FROM users WHERE status = 'A' AND created_at > SYSDATE - 30"
        ).getResultList();
    }

    // ── Case B: Variable reference ──────────────────────────────────────────────

    public List<Object[]> findByDepartment(String dept) {
        String sql = "SELECT u.id, u.name FROM users u WHERE u.department = :dept AND ROWNUM < 100";
        return em.createNativeQuery(sql).setParameter("dept", dept).getResultList();
    }

    // ── Case C: Binary concatenation ───────────────────────────────────────────

    public List<Object[]> findHighValueOrders() {
        String sql = "SELECT o.id, o.total, NVL(o.discount, 0) " +
                     "FROM orders o " +
                     "WHERE o.total > 1000 " +
                     "AND o.status != 'CANCELLED'";
        return em.createNativeQuery(sql).getResultList();
    }

    // ── Case D: Incremental construction (+=) ──────────────────────────────────

    public List<Object[]> searchProducts(boolean includeDiscontinued) {
        String sql = "SELECT p.id, p.name, p.price ";
        sql += "FROM products p ";
        sql += "WHERE p.price > 0 ";
        if (includeDiscontinued) {
            // Note: conditional branches are best-effort — tool takes pre-branch value
            sql += "AND p.status IN ('A', 'D') ";
        } else {
            sql += "AND p.status = 'A' ";
        }
        sql += "ORDER BY p.name";
        return em.createNativeQuery(sql).getResultList();
    }

    // ── Case E: Simple method call ─────────────────────────────────────────────

    private String buildAccountQuery() {
        return "SELECT a.id, a.balance, TRUNC(a.created_at) " +
               "FROM accounts a " +
               "WHERE a.balance >= 0 " +
               "AND a.type = DECODE(a.currency, 'USD', 1, 'EUR', 2, 0)";
    }

    public List<Object[]> findAccounts() {
        return em.createNativeQuery(buildAccountQuery()).getResultList();
    }

    // ── JPQL / HQL queries ─────────────────────────────────────────────────────

    public List<Object> findActiveUsers() {
        return em.createQuery(
            "SELECT u FROM User u WHERE u.active = true ORDER BY u.createdAt DESC"
        ).getResultList();
    }

    // ── Oracle hierarchical query ──────────────────────────────────────────────

    public List<Object[]> findOrgHierarchy(Long rootId) {
        String sql = "SELECT employee_id, manager_id, name " +
                     "FROM employees " +
                     "START WITH employee_id = :rootId " +
                     "CONNECT BY PRIOR employee_id = manager_id";
        return em.createNativeQuery(sql).setParameter("rootId", rootId).getResultList();
    }

    // ── Complex Oracle constructs ──────────────────────────────────────────────

    public List<Object[]> findMonthlySummary() {
        return em.createNativeQuery(
            "SELECT TO_CHAR(sale_date, 'YYYY-MM'), SUM(amount), LISTAGG(product_id, ',') " +
            "WITHIN GROUP (ORDER BY product_id) " +
            "FROM sales " +
            "WHERE sale_date >= TRUNC(SYSDATE, 'MM') " +
            "GROUP BY TO_CHAR(sale_date, 'YYYY-MM')"
        ).getResultList();
    }
}
