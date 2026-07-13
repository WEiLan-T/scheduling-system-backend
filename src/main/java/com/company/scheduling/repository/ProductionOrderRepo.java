package com.company.scheduling.repository;

import com.company.scheduling.domain.ProductionOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProductionOrderRepo extends JpaRepository<ProductionOrder, Integer> {
    List<ProductionOrder> findByOrderId(String orderId);

    // 🌟 新增：获取所有订单并按录入时间倒序（用于历史大盘）
    List<ProductionOrder> findAllByOrderByCreatedAtDesc();

    // 🌟 新增：按订单号删除整个订单的所有明细
    void deleteByOrderId(String orderId);
}