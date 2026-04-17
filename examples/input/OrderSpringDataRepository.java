package com.example.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.NamedNativeQuery;
import java.util.List;

/**
 * Spring Data JPA repository — demonstrates @Query annotation extraction.
 */
@NamedNativeQuery(
    name = "Order.findOverdue",
    query = "SELECT o.* FROM orders o WHERE o.due_date < SYSDATE AND o.status = 'OPEN'"
)
public interface OrderSpringDataRepository extends JpaRepository<Object, Long> {

    @Query("SELECT o FROM Order o WHERE o.customerId = :customerId AND o.status = 'ACTIVE'")
    List<Object> findActiveByCustomer(@Param("customerId") Long customerId);

    @Query(value = "SELECT o.id, o.total, NVL(c.name, 'Unknown') AS customer_name " +
                   "FROM orders o LEFT JOIN customers c ON o.customer_id = c.id " +
                   "WHERE o.created_at >= TRUNC(SYSDATE) - 90 " +
                   "AND o.total > 500",
           nativeQuery = true)
    List<Object[]> findRecentHighValueOrders();

    @Query(value = "SELECT DISTINCT p.category, COUNT(*) cnt " +
                   "FROM order_items oi " +
                   "JOIN products p ON oi.product_id = p.id " +
                   "WHERE oi.order_id IN (SELECT id FROM orders WHERE status = 'DELIVERED') " +
                   "GROUP BY p.category " +
                   "HAVING COUNT(*) > 5 " +
                   "ORDER BY cnt DESC",
           nativeQuery = true)
    List<Object[]> getCategoryStats();
}
