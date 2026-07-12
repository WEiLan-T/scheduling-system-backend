package com.company.scheduling.repository;

import com.company.scheduling.domain.ProductionOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductionOrderRepo extends JpaRepository<ProductionOrder, String> {
    // 主键为订单号 (String类型)，用于批量下达和多明细归属管理
}