package com.company.scheduling.service;

import com.company.scheduling.domain.VirtualWarehouse;
import com.company.scheduling.repository.VirtualWarehouseRepo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;

@Service
public class EstimationService {

    private final VirtualWarehouseRepo warehouseRepo;

    public EstimationService(VirtualWarehouseRepo warehouseRepo) {
        this.warehouseRepo = warehouseRepo;
    }

    /**
     * 根据当前虚拟库存和预设产能，估算订单完工时间
     * @param tapePartNumber 零件号
     * @param targetQty 订单目标需求量
     * @return 预计完工所需的小时数
     */
    public String calculateEstimatedCompletionTime(UUID tapePartNumber, BigDecimal targetQty) {
        // 1. 获取虚拟仓库中的当前库存快照
        Optional<VirtualWarehouse> stockOpt = warehouseRepo.findByTapePartNumber(tapePartNumber);

        BigDecimal currentStock = BigDecimal.ZERO;
        if (stockOpt.isPresent()) {
            currentStock = stockOpt.get().getCurrentQty();
        }

        // 2. 如果现有库存已经满足订单，直接返回 0 小时
        if (currentStock.compareTo(targetQty) >= 0) {
            return "库存充足，当前库存: " + currentStock + "，可立即满足交货！预计完工时间: 0 小时。";
        }

        // 3. 计算还需要生产的缺口数量
        BigDecimal shortage = targetQty.subtract(currentStock);

        // 4. 引入机台静态标称产能 (简化版，实际应从 machine_resources 表动态读取)
        // 假设当前安排的织机每小时可生产 150.0 数量单位的带坯
        BigDecimal weavingProductionRatePerHour = new BigDecimal("150.0");

        // 5. 物料平衡公式推导：所需时间 = 缺口数量 / 每小时生产率
        BigDecimal estimatedHours = shortage.divide(weavingProductionRatePerHour, 2, RoundingMode.HALF_UP);

        return String.format("当前库存: %s, 订单缺口: %s。按当前产能估算，预计还需 %s 小时完工。",
                currentStock, shortage, estimatedHours);
    }
}