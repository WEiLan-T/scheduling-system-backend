package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.VirtualWarehouse;
import com.company.scheduling.repository.VirtualWarehouseRepo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 带坯库存整根贪心消耗器（排产与询单共享逻辑）
 *
 * 规则：
 * 1. 查询键为带坯零件号（由成品零件号经 ProductProcess 反查得到），而非成品零件号；
 * 2. 仅 machineNo 为空（已落库）且 splitSeq 为 null/0（整根）的记录计入可用库存；
 * 3. 同一 tapeCode 跨快照只取最新一期的米数（避免跨快照重复累计），并按其首次出现的
 *    快照日期做 FIFO（旧→新）排序；
 * 4. 逐卷（整根）扣减直至满足需求量，最后一根可能略超需求，超额部分以 surplusMeters
 *    明示标注，不静默截断；仅缺口部分进入织造缺口计算。
 */
@Component
public class TapeStockConsumer {

    private final VirtualWarehouseRepo warehouseRepo;

    public TapeStockConsumer(VirtualWarehouseRepo warehouseRepo) {
        this.warehouseRepo = warehouseRepo;
    }

    /** 被消耗的一卷整根带坯 */
    public static class ConsumedRoll {
        public final String tapeCode;
        public final BigDecimal meters;
        public final LocalDate firstSnapshotDate;

        ConsumedRoll(String tapeCode, BigDecimal meters, LocalDate firstSnapshotDate) {
            this.tapeCode = tapeCode;
            this.meters = meters;
            this.firstSnapshotDate = firstSnapshotDate;
        }

        /** 明细清单条目：{tapeCode, meters} */
        public Map<String, Object> toDraftEntry() {
            Map<String, Object> entry = new HashMap<>();
            entry.put("tapeCode", tapeCode);
            entry.put("meters", meters);
            return entry;
        }
    }

    /** 消耗结果：已消耗整根清单、织造缺口、最后一根投入超额米数 */
    public static class ConsumptionResult {
        private final List<ConsumedRoll> rolls;
        private final BigDecimal shortfall;
        private final BigDecimal surplusMeters;

        ConsumptionResult(List<ConsumedRoll> rolls, BigDecimal shortfall, BigDecimal surplusMeters) {
            this.rolls = rolls;
            this.shortfall = shortfall;
            this.surplusMeters = surplusMeters;
        }

        public List<ConsumedRoll> getRolls() { return rolls; }
        /** 织造缺口（需求量 - 已消耗整根米数，>=0），仅缺口进入织造 */
        public BigDecimal getShortfall() { return shortfall; }
        /** 最后一根整根投入超出需求的米数（>=0），需在明细中明示，不静默截断 */
        public BigDecimal getSurplusMeters() { return surplusMeters; }
        /** 已消耗整根总米数（直接进入共挤生产） */
        public BigDecimal getConsumedMeters() {
            return rolls.stream().map(r -> r.meters).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * 按带坯零件号做整根贪心消耗（快照日期 FIFO：旧→新）
     *
     * @param tapePartNumber 带坯零件号（ProductProcess.getTapePartNumber()）
     * @param metersNeeded   需求米数
     */
    public ConsumptionResult consume(String tapePartNumber, BigDecimal metersNeeded) {
        if (metersNeeded == null || metersNeeded.compareTo(BigDecimal.ZERO) <= 0) {
            return new ConsumptionResult(Collections.emptyList(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        if (tapePartNumber == null || tapePartNumber.isBlank()) {
            return new ConsumptionResult(Collections.emptyList(), metersNeeded, BigDecimal.ZERO);
        }

        // 同一 tapeCode 跨快照去重：最新一期快照的米数为当前库存，最早出现的快照日期作为 FIFO 排序依据
        Map<String, VirtualWarehouse> latestByTapeCode = new HashMap<>();
        Map<String, LocalDate> firstSeenDate = new HashMap<>();
        for (VirtualWarehouse w : warehouseRepo.findByPartNumber(tapePartNumber)) {
            if (w.getMachineNo() != null && !w.getMachineNo().isBlank()) continue; // 在产未落库，不计入可用库存
            Integer seq = w.getSplitSeq();
            if (seq != null && seq != 0) continue; // 分切件，仅整根参与消耗
            BigDecimal meters = w.getStockMeters();
            if (meters == null || meters.compareTo(BigDecimal.ZERO) <= 0) continue;

            String tc = (w.getTapeCode() != null && !w.getTapeCode().isBlank()) ? w.getTapeCode() : ("ROW#" + w.getId());
            LocalDate sd = w.getSnapshotDate() != null ? w.getSnapshotDate() : LocalDate.MIN;
            firstSeenDate.merge(tc, sd, (a, b) -> a.isBefore(b) ? a : b);
            latestByTapeCode.merge(tc, w, (oldW, newW) -> {
                LocalDate od = oldW.getSnapshotDate() != null ? oldW.getSnapshotDate() : LocalDate.MIN;
                LocalDate nd = newW.getSnapshotDate() != null ? newW.getSnapshotDate() : LocalDate.MIN;
                return nd.isAfter(od) ? newW : oldW;
            });
        }

        // FIFO：旧快照（先入库）→新快照
        List<ConsumedRoll> candidates = latestByTapeCode.entrySet().stream()
                .map(e -> new ConsumedRoll(e.getKey(), e.getValue().getStockMeters(), firstSeenDate.get(e.getKey())))
                .sorted(Comparator.comparing((ConsumedRoll r) -> r.firstSnapshotDate).thenComparing(r -> r.tapeCode))
                .collect(Collectors.toList());

        // 贪心：逐卷（整根）扣减直至满足需求量
        List<ConsumedRoll> consumed = new ArrayList<>();
        BigDecimal accumulated = BigDecimal.ZERO;
        for (ConsumedRoll roll : candidates) {
            if (accumulated.compareTo(metersNeeded) >= 0) break;
            consumed.add(roll);
            accumulated = accumulated.add(roll.meters);
        }
        BigDecimal shortfall = metersNeeded.subtract(accumulated).max(BigDecimal.ZERO);
        BigDecimal surplus = accumulated.subtract(metersNeeded).max(BigDecimal.ZERO);
        return new ConsumptionResult(consumed, shortfall, surplus);
    }

    /** 整根按产线分配的结果 */
    public static class RollDistribution {
        /** 每条共挤产线分配到的整根清单（{tapeCode, meters}），长度与产线数一致 */
        public final List<List<Map<String, Object>>> byLine;
        /** 最后一根整根所在的产线下标（超额标注挂载处），无消耗时为 -1 */
        public final int lastRollLineIndex;

        RollDistribution(List<List<Map<String, Object>>> byLine, int lastRollLineIndex) {
            this.byLine = byLine;
            this.lastRollLineIndex = lastRollLineIndex;
        }
    }

    /**
     * 将已消耗整根按长度序列分配到共挤产线：按顺序填充，单线累计达到配额后换下一线，
     * 最后一根整根不拆分，其超出需求的尾部计入末线（由 surplusMeters 明示）。
     */
    public static RollDistribution distributeByLine(List<ConsumedRoll> rolls, int lineCount, BigDecimal perLineQuota) {
        int lanes = Math.max(1, lineCount);
        List<List<Map<String, Object>>> byLine = new ArrayList<>();
        for (int i = 0; i < lanes; i++) byLine.add(new ArrayList<>());
        if (rolls == null || rolls.isEmpty()) return new RollDistribution(byLine, -1);

        BigDecimal quota = perLineQuota != null ? perLineQuota : BigDecimal.ZERO;
        int lineIdx = 0;
        int lastIndex = -1;
        BigDecimal accumulated = BigDecimal.ZERO;
        for (ConsumedRoll roll : rolls) {
            if (lineIdx < lanes - 1 && accumulated.signum() > 0 && accumulated.compareTo(quota) >= 0) {
                lineIdx++;
                accumulated = BigDecimal.ZERO;
            }
            byLine.get(lineIdx).add(roll.toDraftEntry());
            accumulated = accumulated.add(roll.meters);
            lastIndex = lineIdx;
        }
        return new RollDistribution(byLine, lastIndex);
    }
}
