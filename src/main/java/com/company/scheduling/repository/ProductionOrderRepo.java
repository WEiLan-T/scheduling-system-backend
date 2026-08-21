package com.company.scheduling.repository;

import com.company.scheduling.domain.ProductionOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface ProductionOrderRepo extends JpaRepository<ProductionOrder, Integer> {
    List<ProductionOrder> findByOrderId(String orderId);
    List<ProductionOrder> findAllByOrderByCreatedAtDesc();
    void deleteByOrderId(String orderId);

    // 🌟 新增：联合校验去重接口
    boolean existsByOrderIdAndFinishedPartNumber(String orderId, String finishedPartNumber);

    // 🌟 新增：批量查询订单
    List<ProductionOrder> findByOrderIdIn(List<String> orderIds);

    /** 按订单下达日期区间查询（供按年导出Excel，避免全量导出自增数据超载） */
    List<ProductionOrder> findByOrderDateBetween(LocalDate start, LocalDate end);

    /**
     * 分页/搜索/筛选查询（供 list 端点可选分页使用）。
     * keyword 模糊匹配：订单号/零件号/品名/客户名称；筛选列：订单号/零件号。
     * 所有参数均可为 null（=不过滤）。
     * 注：运行库存在历史遗留 bytea 列，JPQL LOWER(bytea) 无对应函数会报 400，
     * 故改用 native 查询并对所有文本列 CAST(col AS text)，ILIKE 保持大小写不敏感语义；
     * 排序内联在 SQL 中（调用方传无排序 Pageable），countQuery 显式提供避免派生歧义。
     */
    @Query(value = "SELECT * FROM production_orders o WHERE " +
            "(:keyword IS NULL OR CAST(o.order_id AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(o.finished_part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(o.product_name AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(o.customer_name AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:orderId IS NULL OR CAST(o.order_id AS text) = CAST(:orderId AS text)) " +
            "AND (:finishedPartNumber IS NULL OR CAST(o.finished_part_number AS text) = CAST(:finishedPartNumber AS text)) " +
            "ORDER BY o.created_at DESC, o.id DESC",
           countQuery = "SELECT COUNT(*) FROM production_orders o WHERE " +
            "(:keyword IS NULL OR CAST(o.order_id AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(o.finished_part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(o.product_name AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(o.customer_name AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:orderId IS NULL OR CAST(o.order_id AS text) = CAST(:orderId AS text)) " +
            "AND (:finishedPartNumber IS NULL OR CAST(o.finished_part_number AS text) = CAST(:finishedPartNumber AS text))",
           nativeQuery = true)
    Page<ProductionOrder> search(@Param("keyword") String keyword,
                                 @Param("orderId") String orderId,
                                 @Param("finishedPartNumber") String finishedPartNumber,
                                 Pageable pageable);
}