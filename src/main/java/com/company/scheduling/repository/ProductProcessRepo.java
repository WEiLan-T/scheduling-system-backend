package com.company.scheduling.repository;

import com.company.scheduling.domain.ProductProcess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ProductProcessRepo extends JpaRepository<ProductProcess, Integer> {
    // 排产核心：根据销售订单中的成品零件号，精准反查工艺BOM与经纬线配置
    Optional<ProductProcess> findByFinishedPartNumber(String finishedPartNumber);
}