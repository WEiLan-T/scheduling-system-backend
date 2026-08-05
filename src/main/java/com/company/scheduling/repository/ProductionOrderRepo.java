package com.company.scheduling.repository;

import com.company.scheduling.domain.ProductionOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
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
}