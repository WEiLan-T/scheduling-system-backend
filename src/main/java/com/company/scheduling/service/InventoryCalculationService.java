package com.company.scheduling.service;

import com.company.scheduling.domain.VirtualWarehouse;
import com.company.scheduling.dto.InventoryDailySummaryDTO;
import com.company.scheduling.repository.CoexDailyLogRepo;
import com.company.scheduling.repository.VirtualWarehouseRepo;
import com.company.scheduling.repository.WeavingDailyLogRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 日库存推算服务
 * 推算公式：某日库存 = 最近一期月度权威快照（锚点） + Σ织造台账产量(按账期日) − Σ共挤台账消耗(按账期日)
 * 月度锚点截断推算范围：只累加锚点快照日期之后至目标日期的台账（SQL GROUP BY 聚合）。
 * 机台非空（在产未落库）的快照行不计入可用库存。
 */
@Service
public class InventoryCalculationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryCalculationService.class);

    /**
     * 无锚点时台账累计的安全下界。
     * 不得使用 LocalDate.MIN（年份越界，作 JDBC 参数绑定时 PostgreSQL 会报"时间戳超出范围"）
     */
    private static final LocalDate EARLIEST_LEDGER_DATE = LocalDate.of(2000, 1, 1);

    @Autowired
    private VirtualWarehouseRepo warehouseRepo;

    @Autowired
    private WeavingDailyLogRepo weavingLogRepo;

    @Autowired
    private CoexDailyLogRepo coexLogRepo;

    /**
     * 查询单日推算库存（按 tapeCode 维度）
     */
    public List<InventoryDailySummaryDTO> calculateDailySummary(LocalDate date) {
        if (date == null) throw new RuntimeException("推算日期不能为空！");
        return calculateForDate(date);
    }

    /**
     * 查询日期区间内逐日推算库存（按 tapeCode 维度）
     */
    public List<InventoryDailySummaryDTO> calculateDailySummary(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) throw new RuntimeException("起止日期不能为空！");
        if (startDate.isAfter(endDate)) throw new RuntimeException("起始日期不能晚于结束日期！");
        List<InventoryDailySummaryDTO> result = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            result.addAll(calculateForDate(d));
        }
        return result;
    }

    /**
     * 单日推算核心逻辑：
     * 1. 定位月度锚点：不超过目标日期的最新快照日期；
     * 2. 加载锚点快照中已落库（机台为空）的记录，按零件号分组；
     * 3. SQL GROUP BY 聚合 (锚点日, 目标日] 区间的织造产量与共挤消耗（按带坯零件号维度）；
     * 4. 同一零件号存在多个带坯编号时，增量按锚点库存占比分摊，保证零件号总量不重复累计。
     */
    private List<InventoryDailySummaryDTO> calculateForDate(LocalDate date) {
        // 1. 月度锚点
        LocalDate anchorDate = warehouseRepo.findLatestSnapshotDateOnOrBefore(date);
        List<VirtualWarehouse> anchors = anchorDate == null
                ? Collections.emptyList()
                : warehouseRepo.findBySnapshotDateAndMachineNoIsNull(anchorDate);
        // 锚点截断：无锚点时从安全下界开始累计全部台账（不可用 LocalDate.MIN，会导致 JDBC 日期越界）
        LocalDate windowStart = anchorDate != null ? anchorDate : EARLIEST_LEDGER_DATE;

        // 2. 锚点快照按零件号分组（保留 tapeCode 明细）
        Map<String, List<VirtualWarehouse>> anchorsByPart = new LinkedHashMap<>();
        for (VirtualWarehouse vw : anchors) {
            anchorsByPart.computeIfAbsent(vw.getPartNumber(), k -> new ArrayList<>()).add(vw);
        }

        // 3. 台账聚合（SQL GROUP BY，只累加锚点之后至目标日的部分）
        Map<String, BigDecimal> weavingByPart = toSumMap(weavingLogRepo.sumOutputByPartNumber(windowStart, date));
        Map<String, BigDecimal> coexByPart = toSumMap(coexLogRepo.sumConsumptionByTapePartNumber(windowStart, date));

        // 4. 合并零件号全集并逐项推算
        Set<String> partNumbers = new LinkedHashSet<>();
        partNumbers.addAll(anchorsByPart.keySet());
        partNumbers.addAll(weavingByPart.keySet());
        partNumbers.addAll(coexByPart.keySet());

        List<InventoryDailySummaryDTO> result = new ArrayList<>();
        for (String partNumber : partNumbers) {
            if (partNumber == null) continue;
            List<VirtualWarehouse> rows = anchorsByPart.getOrDefault(partNumber, Collections.emptyList());
            BigDecimal added = weavingByPart.getOrDefault(partNumber, BigDecimal.ZERO);
            BigDecimal consumed = coexByPart.getOrDefault(partNumber, BigDecimal.ZERO);

            if (rows.isEmpty()) {
                // 锚点中无该零件号（新品），仅呈现台账增量
                if (added.signum() == 0 && consumed.signum() == 0) continue;
                result.add(buildDto(date, partNumber, "DEFAULT", anchorDate,
                        BigDecimal.ZERO, added, consumed));
                continue;
            }

            if (rows.size() == 1) {
                VirtualWarehouse vw = rows.get(0);
                BigDecimal base = zeroIfNull(vw.getStockMeters());
                result.add(buildDto(date, partNumber, vw.getTapeCode(), anchorDate, base, added, consumed));
                continue;
            }

            // 多个带坯编号共享同一零件号：增量按锚点库存占比分摊（基准为0时均摊）
            BigDecimal baseTotal = BigDecimal.ZERO;
            for (VirtualWarehouse vw : rows) baseTotal = baseTotal.add(zeroIfNull(vw.getStockMeters()));
            for (VirtualWarehouse vw : rows) {
                BigDecimal base = zeroIfNull(vw.getStockMeters());
                BigDecimal weight = baseTotal.signum() != 0
                        ? base.divide(baseTotal, 6, RoundingMode.HALF_UP)
                        : BigDecimal.ONE.divide(BigDecimal.valueOf(rows.size()), 6, RoundingMode.HALF_UP);
                result.add(buildDto(date, partNumber, vw.getTapeCode(), anchorDate, base,
                        added.multiply(weight).setScale(2, RoundingMode.HALF_UP),
                        consumed.multiply(weight).setScale(2, RoundingMode.HALF_UP)));
            }
        }
        log.debug("日库存推算 date={}, anchor={}, items={}", date, anchorDate, result.size());
        return result;
    }

    private InventoryDailySummaryDTO buildDto(LocalDate date, String partNumber, String tapeCode,
                                              LocalDate anchorDate, BigDecimal anchorStock,
                                              BigDecimal weavingAdded, BigDecimal coexConsumed) {
        InventoryDailySummaryDTO dto = new InventoryDailySummaryDTO();
        dto.setDate(date);
        dto.setPartNumber(partNumber);
        dto.setTapeCode(tapeCode != null ? tapeCode : "DEFAULT");
        dto.setAnchorDate(anchorDate);
        dto.setAnchorStock(anchorStock);
        dto.setWeavingAdded(weavingAdded);
        dto.setCoexConsumed(coexConsumed);
        dto.setEstimatedStock(anchorStock.add(weavingAdded).subtract(coexConsumed));
        return dto;
    }

    /** 将 GROUP BY 聚合结果(Object[]{key, sum})转为 Map */
    private Map<String, BigDecimal> toSumMap(List<Object[]> rows) {
        Map<String, BigDecimal> map = new HashMap<>();
        if (rows == null) return map;
        for (Object[] row : rows) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) continue;
            map.merge(row[0].toString(), new BigDecimal(row[1].toString()), BigDecimal::add);
        }
        return map;
    }

    private BigDecimal zeroIfNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
