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
        int originalPlannedDays = request.getPlannedProductionDays() != null ? request.getPlannedProductionDays() : 30;
        int plannedDays = originalPlannedDays;
        int bufferDays = request.getGlobalBufferDays() != null ? request.getGlobalBufferDays() : 3;
        // 算法简化：已取消 weavingAdvanceDays，共挤开始时间直接对齐织造结束时间

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

        // 0. 产能利用率检验（预估 pass）：按当前拟生产天数预估各明细的推荐资源数与利用率，
        //    利用率 < 90%（产能富余 > 10%）时，将拟生产天数压缩为触发明细中"实际所需天数"的最大值
        //    （实际所需天数 = 需求 / (日产能 × 资源数) 向上取整）；压缩后主循环按新拟天数自动重算
        //    推荐资源数，甘特图行日期同步更新。TapeStockConsumer.consume 为纯只读模拟，双 pass 安全。
        int requiredDaysOverall = 0;
        boolean needCompressDays = false;
        for (InquiryRequest.InquiryItem item : items) {
            String fPn = item.getFinishedPartNumber();
            if (fPn == null || fPn.isBlank()) continue;
            ProductProcess proc = processRepo.findByFinishedPartNumber(fPn).orElse(null);
            if (proc == null) continue;
            BigDecimal preMeters = calcFinishedMeters(item);
            if (preMeters == null || preMeters.compareTo(BigDecimal.ZERO) <= 0) continue;
            TapeStockConsumer.ConsumptionResult preStock = stockConsumer.consume(proc.getTapePartNumber(), proc.getTapeModelSpec(), preMeters);
            BigDecimal preShortfall = preStock.getShortfall();
            InquiryRequest.ItemResourceOverride ov = overrideMap.get(fPn);
            BigDecimal preWCap = capSnapshot.resolveWeavingCapacity(proc.getTapePartNumber(), fPn,
                    ov != null ? ov.getManualWeavingCapacity() : null);
            BigDecimal preCCap = capSnapshot.resolveCoexCapacity(fPn, proc.getTapePartNumber(),
                    ov != null ? ov.getManualCoexCapacity() : null);
            BigDecimal preDaysBD = new BigDecimal(plannedDays);
            int preMCount = preShortfall.compareTo(preWCap.multiply(preDaysBD)) > 0
                    ? preShortfall.divide(preWCap.multiply(preDaysBD), 4, RoundingMode.HALF_UP).setScale(0, RoundingMode.CEILING).intValue() : 1;
            int preLCount = preMeters.compareTo(preCCap.multiply(preDaysBD)) > 0
                    ? preMeters.divide(preCCap.multiply(preDaysBD), 4, RoundingMode.HALF_UP).setScale(0, RoundingMode.CEILING).intValue() : 1;
            if (ov != null) {
                if (ov.getMachineCount() != null && ov.getMachineCount() > 0) preMCount = ov.getMachineCount();
                if (ov.getLineCount() != null && ov.getLineCount() > 0) preLCount = ov.getLineCount();
            }
            // 利用率 = 需求 / (日产能 × 拟天数 × 资源数)；织造无缺口时视为 1（无产能压力）
            BigDecimal preWUtil = preShortfall.compareTo(BigDecimal.ZERO) > 0
                    ? preShortfall.divide(preWCap.multiply(preDaysBD).multiply(new BigDecimal(preMCount)), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ONE;
            BigDecimal preCUtil = preMeters.divide(preCCap.multiply(preDaysBD).multiply(new BigDecimal(preLCount)), 4, RoundingMode.HALF_UP);
            if (preWUtil.min(preCUtil).compareTo(new BigDecimal("0.9")) >= 0) continue;
            needCompressDays = true;
            // 实际所需天数 = 需求 / (日产能 × 资源数) 向上取整（仅计算有需求的部分）
            int preRequiredDays = 1;
            if (preShortfall.compareTo(BigDecimal.ZERO) > 0 && preMCount > 0) {
                preRequiredDays = Math.max(preRequiredDays,
                        preShortfall.divide(preWCap.multiply(new BigDecimal(preMCount)), 0, RoundingMode.CEILING).intValue());
            }
            if (preLCount > 0) {
                preRequiredDays = Math.max(preRequiredDays,
                        preMeters.divide(preCCap.multiply(new BigDecimal(preLCount)), 0, RoundingMode.CEILING).intValue());
            }
            requiredDaysOverall = Math.max(requiredDaysOverall, preRequiredDays);
        }
        // 压缩拟生产天数（只减不增）：保证所有明细都能在压缩后拟天数内由重算后的资源数完成
        if (needCompressDays && requiredDaysOverall > 0 && requiredDaysOverall < plannedDays) {
            plannedDays = requiredDaysOverall;
        }

        List<Map<String, Object>> itemSchedules = new ArrayList<>();
        // 需求6：带坯供给模拟冲突告警收集容器
        List<String> warnings = new ArrayList<>();
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
            TapeStockConsumer.ConsumptionResult stock = stockConsumer.consume(tapePartNumber, proc.getTapeModelSpec(), finishedMeters);
            BigDecimal shortfall = stock.getShortfall();

            // 3. 解析日产能：人工覆盖 > 工艺库标准值 > MISSING_CAPACITY 熔断
            InquiryRequest.ItemResourceOverride override = overrideMap.get(fPn);
            BigDecimal wCap = capSnapshot.resolveWeavingCapacity(tapePartNumber, fPn,
                    override != null ? override.getManualWeavingCapacity() : null);
            BigDecimal cCap = capSnapshot.resolveCoexCapacity(fPn, tapePartNumber,
                    override != null ? override.getManualCoexCapacity() : null);
            // 织造储备库存（与排产引擎同一算法）：reserveMeters = cCap×reserveDays，
            // extraAdvanceDays = ceil(reserveMeters/wCap)；仅作织造开工时点偏移，不计入缺口米数；
            // reserveDays 缺省 0 时与现状询单结果完全一致
            BigDecimal[] reserve = SchedulingEngine.calcWeavingReserve(request.getWeavingReserveDays(), wCap, cCap);
            BigDecimal reserveMeters = reserve[0];
            int reserveAdvanceDays = reserve[1].intValue();

            // 4. 计算推荐资源数（除零保护：wCap/cCap 已由 CapacityProvider 保证 > 0，但加显式 guard）
            if (wCap.compareTo(BigDecimal.ZERO) <= 0) wCap = new BigDecimal("1");
            if (cCap.compareTo(BigDecimal.ZERO) <= 0) cCap = new BigDecimal("1");
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

            // 匹配候选机台/产线：传完整规格字符串走区间判定（spec 区间落入 limit 区间）；
            // 候选为空时告警写入 warnings 容器，随询单响应的 conflictWarnings 键透传至前端
            String caliberSpec = proc.getFinishedModelSpec();
            List<WeavingMachineStatus> candidateWMachines = capacityMatcher.findBestWeavingMachinesBySpec(caliberSpec, allWMachines, warnings);
            List<CoexLineStatus> candidateCLines = capacityMatcher.findBestCoexLinesBySpec(caliberSpec, allCLines, warnings);

            // 6. 独立出行模型（对齐 SchedulingEngine）：织造侧按 machineCount 独立出行、共挤侧按 lineCount 独立出行，
            // 支持任意 N:M 组合（如 4对2 → 4 行织造 + 2 行共挤）；废除 splitCount=max(...) 配对模型
            int machineCount = recommendedMachineCount;
            int lineCount = recommendedLineCount;

            // 请求数超过物理候选数时不再静默截断：按请求数量出行，超出候选的行保持待指派（null）并写入 warnings
            if (machineCount > candidateWMachines.size()) {
                warnings.add("成品 [" + fPn + "] 织造机台请求 " + machineCount + " 台，口径匹配候选仅 " + candidateWMachines.size() + " 台，超出部分行待指派");
            }
            if (lineCount > candidateCLines.size()) {
                warnings.add("成品 [" + fPn + "] 共挤产线请求 " + lineCount + " 条，口径匹配候选仅 " + candidateCLines.size() + " 条，超出部分行待指派");
            }

            totalRecommendedMachines += recommendedMachineCount;
            totalRecommendedLines += recommendedLineCount;

            // 7. 配额：splitShortfall 仅作用于新织造缺口部分（每台机台一份），splitFinished 为共挤总米数（每产线一份）
            BigDecimal splitShortfall = machineCount > 0 ? shortfall.divide(new BigDecimal(machineCount), 4, RoundingMode.HALF_UP) : shortfall;
            BigDecimal splitFinished = lineCount > 0 ? finishedMeters.divide(new BigDecimal(lineCount), 4, RoundingMode.HALF_UP) : finishedMeters;

            // 已消耗整根按 lineCount 基数分桶分配到共挤产线（与排产链路口径一致）
            TapeStockConsumer.RollDistribution rollDistribution = TapeStockConsumer.distributeByLine(stock.getRolls(), lineCount, splitFinished);

            Set<String> usedW = new HashSet<>();
            Set<String> usedC = new HashSet<>();

            // 如果有指定机台/产线：仅保留命中候选的 ID，其余行回落候选池自动选取
            List<String> assignedMachineIds = (override != null && override.getAssignedMachineIds() != null) ? override.getAssignedMachineIds() : Collections.emptyList();
            List<String> assignedLineIds = (override != null && override.getAssignedLineIds() != null) ? override.getAssignedLineIds() : Collections.emptyList();
            List<WeavingMachineStatus> selectedWMachines = new ArrayList<>();
            List<CoexLineStatus> selectedCLines = new ArrayList<>();
            for (String mid : assignedMachineIds) {
                candidateWMachines.stream().filter(m -> m.getMachineId().equals(mid)).findFirst().ifPresent(selectedWMachines::add);
            }
            for (String lid : assignedLineIds) {
                candidateCLines.stream().filter(l -> l.getLineId().equals(lid)).findFirst().ifPresent(selectedCLines::add);
            }

            // 8. 正排时间推导（同一成品各行共享同一基准时点）；
            // 每台机台织造量 = 缺口均分（储备仅决定共挤开机时点，不计入织造总量，避免超量排产）
            BigDecimal totalWeavePerMachine = splitShortfall;
            BigDecimal splitWHours = totalWeavePerMachine.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
            BigDecimal splitCHours = splitFinished.divide(cCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
            // 正向排产：从现在起算，共挤开机时点由下方 coexStartRef 的储备提前天数控制
            LocalDateTime weavingStartRef = now;
            LocalDateTime weavingEndRef = now.plusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue());
            // 共挤开机：仅在有织造缺口时才应用储备提前天数；shortfall=0 时直接从 now 开始
            LocalDateTime coexStartRef;
            if (shortfall.compareTo(BigDecimal.ZERO) > 0) {
                coexStartRef = now.plusDays(reserveAdvanceDays);
            } else {
                coexStartRef = now;
            }
            LocalDateTime coexEndRef = coexStartRef.plusMinutes(splitCHours.multiply(new BigDecimal("60")).longValue());
            // 安全保障：共挤停机不早于织造停机（共挤连续不停机）
            if (coexEndRef.isBefore(weavingEndRef)) {
                coexEndRef = weavingEndRef;
            }

            // 9. 织造独立出行：每台机台一行织造资源行
            List<String> weavingMachineIds = new ArrayList<>();
            for (int i = 0; i < machineCount; i++) {
                WeavingMachineStatus wm;
                if (i < selectedWMachines.size()) {
                    wm = selectedWMachines.get(i);
                } else {
                    wm = candidateWMachines.stream()
                            .filter(m -> !usedW.contains(m.getMachineId()))
                            .max(Comparator.comparingInt(m -> capacityMatcher.scoreWeavingMachine(m, proc.getWarpSpec(), caliberSpec, allWMachines)))
                            .orElse(null);
                }
                if (wm != null) {
                    usedW.add(wm.getMachineId());
                    weavingMachineIds.add(wm.getMachineId());
                }

                ScheduleDates wDates = new ScheduleDates();
                wDates.startDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingStartRef : null;
                wDates.endDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingEndRef : null;
                wDates.algoWeavingCapacity = wCap;
                ScheduleDates cDates = new ScheduleDates();
                cDates.algoCoexCapacity = cCap;

                Map<String, Object> draftItem = buildDraftView(fPn, tapePartNumber,
                        proc.getWarpSpec(), proc.getWeftSpec(),
                        proc.getFinishedModelSpec(), proc.getTapeModelSpec(),
                        splitFinished, splitShortfall, wDates, cDates,
                        Collections.emptyList(), null,
                        reserveMeters, reserveAdvanceDays, proc);
                draftItem.put("plannedMachine", wm != null ? wm.getMachineId() : null);
                draftItem.put("plannedLine", null);
                draftItem.put("allocationType", "weaving");
                draftItem.put("candidateMachineIds", candidateWMachines.stream()
                        .map(WeavingMachineStatus::getMachineId).collect(Collectors.toList()));
                draftItem.put("candidateLineIds", candidateCLines.stream()
                        .map(CoexLineStatus::getLineId).collect(Collectors.toList()));
                itemSchedules.add(draftItem);

                // 更新 overall 时间范围
                if (wDates.startDate != null && wDates.startDate.isBefore(overallStartDate)) overallStartDate = wDates.startDate;
                if (wDates.endDate != null && wDates.endDate.isAfter(overallEndDate)) overallEndDate = wDates.endDate;
            }

            // 10. 共挤独立出行：每产线一行共挤资源行；织造机台供给段按 j*lineCount/machineCount 比例归属本产线
            for (int i = 0; i < lineCount; i++) {
                CoexLineStatus cl;
                if (i < selectedCLines.size()) {
                    cl = selectedCLines.get(i);
                } else {
                    cl = candidateCLines.stream().filter(l -> !usedC.contains(l.getLineId())).findFirst().orElse(null);
                }
                if (cl != null) usedC.add(cl.getLineId());

                ScheduleDates wDates = new ScheduleDates();
                wDates.algoWeavingCapacity = wCap;
                ScheduleDates cDates = new ScheduleDates();
                cDates.startDate = coexStartRef;
                cDates.endDate = coexEndRef;
                cDates.algoCoexCapacity = cCap;

                Map<String, Object> draftItem = buildDraftView(fPn, tapePartNumber,
                        proc.getWarpSpec(), proc.getWeftSpec(),
                        proc.getFinishedModelSpec(), proc.getTapeModelSpec(),
                        splitFinished, splitShortfall, wDates, cDates,
                        rollDistribution.byLine.get(i), i == rollDistribution.lastRollLineIndex ? stock.getSurplusMeters() : null,
                        reserveMeters, reserveAdvanceDays, proc);
                draftItem.put("plannedMachine", null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                draftItem.put("allocationType", "coex");
                // 需求6同构接入：模拟共挤中途换带坯时点（事件基于共挤行的供给段，仅挂载 draftItem 不落库）：
                // 供给段 = 该产线分配到的库存整根 + 按比例映射到本产线的织造机台（每台可供米数=splitShortfall）
                draftItem.put("tapeChangeEvents", TapeSupplySimulator.simulateTapeChanges(
                        cl != null ? cl.getLineId() : null, coexStartRef, cCap, wCap, reserveMeters,
                        buildLineSupplySegments(rollDistribution.byLine.get(i), weavingMachineIds, lineCount, i, splitShortfall),
                        warnings));
                draftItem.put("candidateMachineIds", candidateWMachines.stream()
                        .map(WeavingMachineStatus::getMachineId).collect(Collectors.toList()));
                draftItem.put("candidateLineIds", candidateCLines.stream()
                        .map(CoexLineStatus::getLineId).collect(Collectors.toList()));
                itemSchedules.add(draftItem);

                // 更新 overall 时间范围
                if (cDates.startDate.isBefore(overallStartDate)) overallStartDate = cDates.startDate;
                if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;
            }
        }

        if (overallStartDate == LocalDateTime.MAX) overallStartDate = now;
        if (overallEndDate == LocalDateTime.MIN) overallEndDate = now.plusDays(plannedDays);

        long totalDays = ChronoUnit.DAYS.between(overallStartDate.toLocalDate(), overallEndDate.toLocalDate()) + 1;

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", "询单预估");
        result.put("inquiryMode", true);
        result.put("plannedProductionDays", plannedDays);
        // 产能利用率压缩提示：仅当拟生产天数被压缩时返回原始值，前端据此展示压缩说明
        if (plannedDays < originalPlannedDays) {
            result.put("daysCompressedFrom", originalPlannedDays);
        }
        result.put("overallStartDate", overallStartDate.toString());
        result.put("overallEndDate", overallEndDate.toString());
        result.put("totalDays", totalDays);
        result.put("details", itemSchedules);
        result.put("conflictWarnings", warnings);
        result.put("recommendedMachineCount", totalRecommendedMachines);
        result.put("recommendedLineCount", totalRecommendedLines);
        return result;
    }

    // ================ 内部方法 ================

    /**
     * 构建某条共挤产线的带坯供给段序列（供 TapeSupplySimulator 递推切换时点），与 SchedulingEngine 同构。
     * 顺序：先库存整根（rollDistribution 已按产线分配，{tapeCode, meters}），后织造机台。
     * 机台→产线映射：machineCount 与 lineCount 独立，按 j*lineCount/machineCount 比例归属，
     * 支持 2对1、4对2、3对2 等任意组合；每台机台可供米数 = splitShortfall 配额。
     */
    private List<TapeSupplySimulator.SupplySegment> buildLineSupplySegments(
            List<Map<String, Object>> lineRolls, List<String> weavingMachineIds,
            int lineCount, int lineIndex, BigDecimal splitShortfall) {
        List<TapeSupplySimulator.SupplySegment> supply = new ArrayList<>();
        if (lineRolls != null) {
            for (Map<String, Object> roll : lineRolls) {
                Object meters = roll.get("meters");
                supply.add(TapeSupplySimulator.SupplySegment.stock(
                        String.valueOf(roll.get("tapeCode")),
                        meters instanceof BigDecimal ? (BigDecimal) meters : BigDecimal.ZERO));
            }
        }
        int bound = weavingMachineIds != null ? weavingMachineIds.size() : 0;
        int lanes = Math.max(1, lineCount);
        for (int j = 0; j < bound; j++) {
            if (bound > 0 && (j * lanes) / bound != lineIndex) continue;
            supply.add(TapeSupplySimulator.SupplySegment.machine(weavingMachineIds.get(j), splitShortfall));
        }
        return supply;
    }

    private static class ScheduleDates {
        LocalDateTime startDate;
        LocalDateTime endDate;
        BigDecimal algoWeavingCapacity;
        BigDecimal algoCoexCapacity;
    }

    private Map<String, Object> buildDraftView(String fPn, String tPn, String warp, String weft,
                                                String fSpec, String tSpec,
                                                BigDecimal fMeters, BigDecimal tNeed,
                                                ScheduleDates w, ScheduleDates c,
                                                List<Map<String, Object>> consumedTapeCodes, BigDecimal surplusMeters,
                                                BigDecimal reserveMeters, Integer reserveAdvanceDays,
                                                ProductProcess proc) {
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
        // 织造储备库存展示字段：储备米数与对应提前开工天数（缺省 0）
        m.put("reserveMeters", reserveMeters);
        m.put("reserveAdvanceDays", reserveAdvanceDays);
        // 库存整根消耗清单：[{tapeCode, meters}]；超额米数明示标注，不静默截断
        m.put("consumedTapeCodes", consumedTapeCodes != null ? consumedTapeCodes : Collections.emptyList());
        m.put("surplusMeters", surplusMeters);
        if (surplusMeters != null && surplusMeters.compareTo(BigDecimal.ZERO) > 0) {
            m.put("consumptionRemark", "库存最后一根整根投入超出需求 " + surplusMeters.stripTrailingZeros().toPlainString() + " 米（超额未截断，如实计入投入）");
        }
        // 物料消耗字段：经纬线用线量与用胶量
        if (proc != null) {
            BigDecimal warpWeight = proc.getWarpWeightPerMeter();
            m.put("warpTotalWeightKg", warpWeight != null ? warpWeight.multiply(tNeed).divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP) : null);
            BigDecimal weft3000DWeight = proc.getWeftWeightPerMeter3000D();
            m.put("weft3000DTotalWeightKg", weft3000DWeight != null ? weft3000DWeight.multiply(tNeed).divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP) : null);
            m.put("weftSpec3000D", proc.getWeftSpec3000D());
            BigDecimal weft2000DWeight = proc.getWeftWeightPerMeter2000D();
            m.put("weft2000DTotalWeightKg", weft2000DWeight != null ? weft2000DWeight.multiply(tNeed).divide(new BigDecimal("1000"), 4, RoundingMode.HALF_UP) : null);
            m.put("weftSpec2000D", proc.getWeftSpec2000D());
            BigDecimal glueUsage = proc.getGlueUsagePerMeter();
            m.put("glueTotalKg", glueUsage != null ? glueUsage.multiply(fMeters).setScale(4, RoundingMode.HALF_UP) : null);
            m.put("materialType", proc.getMaterialType());
        } else {
            m.put("warpTotalWeightKg", null);
            m.put("weft3000DTotalWeightKg", null);
            m.put("weftSpec3000D", null);
            m.put("weft2000DTotalWeightKg", null);
            m.put("weftSpec2000D", null);
            m.put("glueTotalKg", null);
            m.put("materialType", null);
        }
        // 计划天数与产能验证字段：供前端甘特图 tooltip 和调度表产能验证列使用；
        // 天数经产能利用率检验：利用率 < 90% 时按实际生产时长压缩天数显示（口径与排产引擎一致）
        long plannedWeavingDays = SchedulingEngine.calcPlannedDays(w.startDate, w.endDate, tNeed, w.algoWeavingCapacity);
        long plannedCoexDays = SchedulingEngine.calcPlannedDays(c.startDate, c.endDate, fMeters, c.algoCoexCapacity);
        m.put("plannedWeavingDays", plannedWeavingDays);
        m.put("plannedCoexDays", plannedCoexDays);
        m.put("weavingCapacityUtilization", calcUtilization(tNeed, w.algoWeavingCapacity, plannedWeavingDays));
        m.put("coexCapacityUtilization", calcUtilization(fMeters, c.algoCoexCapacity, plannedCoexDays));
        return m;
    }

    /**
     * 产能利用率计算：需求米数 / (日产能 × 天数)。
     * >1 表示产能不足，≤1 表示产能充足。日产能或天数为 0 时返回 0。
     */
    private static BigDecimal calcUtilization(BigDecimal demandMeters, BigDecimal dailyCapacity, long days) {
        if (dailyCapacity == null || dailyCapacity.compareTo(BigDecimal.ZERO) <= 0 || days <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal totalCapacity = dailyCapacity.multiply(new BigDecimal(days));
        return demandMeters.divide(totalCapacity, 4, RoundingMode.HALF_UP);
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
