package com.company.scheduling.repository;

import com.company.scheduling.domain.ProductionOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ProductionOrderRepo extends JpaRepository<ProductionOrder, Integer> { // 主键类型改为 Integer
    // 🌟 新增：提取整个订单的所有产品明细行
    List<ProductionOrder> findByOrderId(String orderId);
}