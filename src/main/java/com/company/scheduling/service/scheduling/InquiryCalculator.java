package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.InquiryRequest;
import com.company.scheduling.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 询单计算引擎：无订单号/交货期，按拟生产天数正向排产
 */
@Service
public class InquiryCalculator {

    private final CapacityMatcher capacityMatcher;
    private final ProductProcessRepo processRepo;
    private final WeavingMachineStatusRepo weavingStatusRepo;
    private final CoexLineStatusRepo coexStatusRepo;
    private final CapacityProvider capacityProvider;
    private final TapeStockConsumer stockConsumer;

    public InquiryCalculator(CapacityMatcher capacityMatcher,
                             ProductProcessRepo processRepo,
                             WeavingMachineStatusRepo weavingStatusRepo,
                             CoexLineStatusRepo coexStatusRepo,
                             CapacityProvider capacityProvider,
                             TapeStockConsumer stockConsumer) {
        this.capacityMatcher = capacityMatcher;
        this.processRepo = processRepo;
        this.weavingStatusRepo = weavingStatusRepo;
        this.coexStatusRepo = coexStatusRepo;
        this.capacityProvider = capacityProvider;
        this.stockConsumer = stockConsumer;
    }

    public Map<String, Object> calculateInquiry(InquiryRequest request, String currentUser) {
        List<InquiryRequest.InquiryItem> items = request.getItems();
        if (items == null || items.isEmpty()) {
            throw new RuntimeException("询单明细不能为空！");
        }
        int plannedDays = request.getPlannedProductionDays() != null ? request.getPlannedProductionDays() : 30;
        int bufferDays = request.getGlobalBufferDays() != null ? request.getGlobalBufferDays() : 3;
        int weaveAdvance = request.getWeavingAdvanceDays() != null ? request.getWeavingAdvanceDays() : 2;

        // 建立 override 索引
        Map<String, InquiryRequest.ItemResourceOverride> overrideMap = new HashMap<>();
        if (request.getResourceOverrides() != null) {
            for (InquiryRequest.ItemResourceOverride ov : request.getResourceOverrides()) {
                overrideMap.put(ov.getFinishedPartNumber(), ov);
            }
        }

        LocalDateTime overallStartDate = LocalDateTime.MAX;
        LocalDateTime overallEndDate = LocalDateTime.MIN;
        int totalRecommendedMachines = 0;
        int totalRecommendedLines = 0;

        List<WeavingMachineStatus> allWMachines = weavingStatusRepo.findAll();
        List<CoexLineStatus> allCLines = coexStatusRepo.findAll();

        // 产能快照：一次 findAll 构建工艺库标准产能 O(1) 查找 Map
        CapacityProvider.CapacitySnapshot capSnapshot = capacityProvider.loadSnapshot();

        List<Map<String, Object>> itemSchedules = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (InquiryRequest.InquiryItem item : items) {
            String fPn = item.getFinishedPartNumber();
            if (fPn == null || fPn.isBlank()) {
                throw new RuntimeException("成品零件号不能为空！");
            }

            // 1. 获取工艺参数
            ProductProcess proc = processRepo.findByFinishedPartNumber(fPn)
                    .orElseThrow(() -> new RuntimeException("MISSING_PROCESS:" + fPn));
            String tapePartNumber = proc.getTapePartNumber();

            // 2. 计算需求量（优先 totalLength，其次 metersPerRoll * rollCount）
            BigDecimal finishedMeters = calcFinishedMeters(item);
            if (finishedMeters == null || finishedMeters.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("询单明细 [" + fPn + "] 缺少数量信息，请填写总需求米数或单卷长度×卷数！");
            }
            // 库存整根贪心消耗（与排产同一逻辑）：以带坯零件号查库存，仅计已落库整根，快照日期FIFO；
            // 已消耗整根直接进入共挤生产，仅缺口部分进入织造缺口计算（不再假设库存为0）
            TapeStockConsumer.ConsumptionResult stock = stockConsumer.consume(tapePartNumber, finishedMeters);
            BigDecimal shortfall = stock.getShortfall();

            // 3. 解析日产能：人工覆盖 > 工艺库标准值 > MISSING_CAPACITY 熔断
            InquiryRequest.ItemResourceOverride override = overrideMap.get(fPn);
            BigDecimal wCap = capSnapshot.resolveWeavingCapacity(tapePartNumber, fPn,
                    override != null ? override.getManualWeavingCapacity() : null);
            BigDecimal cCap = capSnapshot.resolveCoexCapacity(fPn, tapePartNumber,
                    override != null ? override.getManualCoexCapacity() : null);

            // 4. 计算推荐资源数
            BigDecimal plannedDaysBD = new BigDecimal(plannedDays);
            int recommendedMachineCount = 1;
            int recommendedLineCount = 1;

            BigDecimal wTotalCap = wCap.multiply(plannedDaysBD);
            BigDecimal cTotalCap = cCap.multiply(plannedDaysBD);

            if (shortfall.compareTo(wTotalCap) > 0) {
                recommendedMachineCount = shortfall.divide(wTotalCap, 4, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.CEILING).intValue();
            }
            if (finishedMeters.compareTo(cTotalCap) > 0) {
                recommendedLineCount = finishedMeters.divide(cTotalCap, 4, RoundingMode.HALF_UP)
                        .setScale(0, RoundingMode.CEILING).intValue();
            }

            // 5. 如果有 override，使用手动指定的数量
            if (override != null) {
                if (override.getMachineCount() != null && override.getMachineCount() > 0) {
                    recommendedMachineCount = override.getMachineCount();
                }
                if (override.getLineCount() != null && override.getLineCount() > 0) {
                    recommendedLineCount = override.getLineCount();
                }
            }

            // 取最大分拆数
            int splitCount = Math.max(recommendedMachineCount, recommendedLineCount);

            // 匹配候选机台/产线
            Double caliber = capacityMatcher.extractCaliber(proc.getFinishedModelSpec());
            List<WeavingMachineStatus> candidateWMachines = capacityMatcher.findBestWeavingMachines(caliber, allWMachines);
            List<CoexLineStatus> candidateCLines = capacityMatcher.findBestCoexLines(caliber, allCLines);

            int maxPhysical = Math.max(1, Math.max(candidateWMachines.size(), candidateCLines.size()));
            if (splitCount > maxPhysical) splitCount = maxPhysical;

            totalRecommendedMachines += recommendedMachineCount;
            totalRecommendedLines += recommendedLineCount;

            // 6. 需求量平分：splitShortfall 仅作用于新织造缺口部分，splitFinished 为共挤总米数
            BigDecimal splitFinished = finishedMeters.divide(new BigDecimal(splitCount), 4, RoundingMode.HALF_UP);
            BigDecimal splitShortfall = shortfall.divide(new BigDecimal(splitCount), 4, RoundingMode.HALF_UP);

            // 已消耗整根按长度序列分配到共挤产线
            TapeStockConsumer.RollDistribution rollDistribution = TapeStockConsumer.distributeByLine(stock.getRolls(), splitCount, splitFinished);

            Set<String> usedW = new HashSet<>();
            Set<String> usedC = new HashSet<>();

            // 如果有指定机台/产线
            List<String> assignedMachineIds = (override != null && override.getAssignedMachineIds() != null) ? override.getAssignedMachineIds() : Collections.emptyList();
            List<String> assignedLineIds = (override != null && override.getAssignedLineIds() != null) ? override.getAssignedLineIds() : Collections.emptyList();

            for (int i = 0; i < splitCount; i++) {
                // 选择产线
                CoexLineStatus cl;
                if (i < assignedLineIds.size()) {
                    String lineId = assignedLineIds.get(i);
                    cl = candidateCLines.stream().filter(l -> lineId.equals(l.getLineId())).findFirst().orElse(null);
                } else {
                    cl = candidateCLines.stream().filter(l -> !usedC.contains(l.getLineId())).findFirst().orElse(null);
                }
                if (cl != null) usedC.add(cl.getLineId());
                Integer targetWs = cl != null ? capacityMatcher.extractWorkshopNumber(cl.getWorkshopId()) : null;

                // 选择机台
                WeavingMachineStatus wm;
                if (i < assignedMachineIds.size()) {
                    String machineId = assignedMachineIds.get(i);
                    wm = candidateWMachines.stream().filter(m -> machineId.equals(m.getMachineId())).findFirst().orElse(null);
                } else {
                    wm = candidateWMachines.stream()
                            .filter(m -> !usedW.contains(m.getMachineId()))
                            .max(Comparator.comparingInt(m -> capacityMatcher.scoreWeavingMachine(m, proc.getWarpSpec(), allWMachines)
                                    + (targetWs != null && targetWs.equals(capacityMatcher.extractWorkshopNumber(m.getWorkshopId())) ? 80 : 0)))
                            .orElse(null);
                }
                if (wm != null) usedW.add(wm.getMachineId());

                // 7. 正向排产时间计算
                BigDecimal splitWHours = splitShortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                BigDecimal splitCHours = splitFinished.divide(cCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));

                // 从 now 开始正向安排
                LocalDateTime weavingStart = now;
                LocalDateTime weavingEnd = now.plusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue());

                // 共挤开始 = 织造结束 - weaveAdvance 天
                LocalDateTime coexStart = weavingEnd.minusDays(weaveAdvance);
                // 如果共挤开始早于织造开始，则共挤开始=织造开始
                if (coexStart.isBefore(weavingStart)) {
                    coexStart = weavingStart;
                }
                LocalDateTime coexEnd = coexStart.plusMinutes(splitCHours.multiply(new BigDecimal("60")).longValue());

                BigDecimal changeoverDays = new BigDecimal("1");
                Integer delayDays = 1;

                ScheduleDates wDates = new ScheduleDates();
                wDates.startDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingStart : null;
                wDates.endDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingEnd : null;
                wDates.algoWeavingCapacity = wCap;
                wDates.algoChangeoverDays = changeoverDays;

                ScheduleDates cDates = new ScheduleDates();
                cDates.startDate = coexStart;
                cDates.endDate = coexEnd;
                cDates.algoCoexCapacity = cCap;
                cDates.algoDelayDays = delayDays;

                Map<String, Object> draftItem = buildDraftView(fPn, tapePartNumber,
                        proc.getWarpSpec(), proc.getWeftSpec(),
                        proc.getFinishedModelSpec(), proc.getTapeModelSpec(),
                        splitFinished, splitShortfall, wDates, cDates,
                        rollDistribution.byLine.get(i), i == rollDistribution.lastRollLineIndex ? stock.getSurplusMeters() : null);
                draftItem.put("plannedMachine", wm != null ? wm.getMachineId() : null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                itemSchedules.add(draftItem);

                // 更新 overall 时间范围
                if (wDates.startDate != null && wDates.startDate.isBefore(overallStartDate)) overallStartDate = wDates.startDate;
                if (cDates.startDate.isBefore(overallStartDate)) overallStartDate = cDates.startDate;
                if (wDates.endDate != null && wDates.endDate.isAfter(overallEndDate)) overallEndDate = wDates.endDate;
                if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;
            }
        }

        if (overallStartDate == LocalDateTime.MAX) overallStartDate = now;
        if (overallEndDate == LocalDateTime.MIN) overallEndDate = now.plusDays(plannedDays);

        long totalDays = ChronoUnit.DAYS.between(overallStartDate.toLocalDate(), overallEndDate.toLocalDate()) + 1;

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", "询单预估");
        result.put("inquiryMode", true);
        result.put("plannedProductionDays", request.getPlannedProductionDays());
        result.put("overallStartDate", overallStartDate.toString());
        result.put("overallEndDate", overallEndDate.toString());
        result.put("totalDays", totalDays);
        result.put("details", itemSchedules);
        result.put("recommendedMachineCount", totalRecommendedMachines);
        result.put("recommendedLineCount", totalRecommendedLines);
        return result;
    }

    // ================ 内部方法 ================

    private static class ScheduleDates {
        LocalDateTime startDate;
        LocalDateTime endDate;
        BigDecimal algoWeavingCapacity;
        BigDecimal algoCoexCapacity;
        BigDecimal algoChangeoverDays;
        Integer algoDelayDays;
    }

    private Map<String, Object> buildDraftView(String fPn, String tPn, String warp, String weft,
                                                String fSpec, String tSpec,
                                                BigDecimal fMeters, BigDecimal tNeed,
                                                ScheduleDates w, ScheduleDates c,
                                                List<Map<String, Object>> consumedTapeCodes, BigDecimal surplusMeters) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", "询单预估");
        m.put("finishedPartNumber", fPn);
        m.put("tapePartNumber", tPn);
        m.put("warpSpec", warp);
        m.put("weftSpec", weft);
        m.put("finishedModelSpec", fSpec);
        m.put("tapeModelSpec", tSpec);
        m.put("finishedMeters", fMeters);
        m.put("tapeMetersNeed", tNeed);
        m.put("weavingStart", w.startDate != null ? w.startDate.toString() : null);
        m.put("weavingEnd", w.endDate != null ? w.endDate.toString() : null);
        m.put("coexStart", c.startDate != null ? c.startDate.toString() : null);
        m.put("coexEnd", c.endDate != null ? c.endDate.toString() : null);
        m.put("weavingCapacity", w.algoWeavingCapacity);
        m.put("coexCapacity", c.algoCoexCapacity);
        m.put("changeoverDays", w.algoChangeoverDays);
        m.put("startDelay", c.algoDelayDays);
        // 库存整根消耗清单：[{tapeCode, meters}]；超额米数明示标注，不静默截断
        m.put("consumedTapeCodes", consumedTapeCodes != null ? consumedTapeCodes : Collections.emptyList());
        m.put("surplusMeters", surplusMeters);
        if (surplusMeters != null && surplusMeters.compareTo(BigDecimal.ZERO) > 0) {
            m.put("consumptionRemark", "库存最后一根整根投入超出需求 " + surplusMeters.stripTrailingZeros().toPlainString() + " 米（超额未截断，如实计入投入）");
        }
        return m;
    }

    private BigDecimal calcFinishedMeters(InquiryRequest.InquiryItem item) {
        if (item.getTotalLength() != null && item.getTotalLength().compareTo(BigDecimal.ZERO) > 0) {
            return item.getTotalLength();
        }
        if (item.getMetersPerRoll() != null && item.getRollCount() != null && item.getRollCount() > 0) {
            return item.getMetersPerRoll().multiply(new BigDecimal(item.getRollCount()));
        }
        return null;
    }

}
