package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.ProductProcess;
import com.company.scheduling.repository.ProductProcessRepo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 产能提供者（任务#5）：排产/询单产能统一取自工艺库标准值，
 * 取代原"历史台账日均产能"算法。
 *
 * <p>取值优先级：请求人工覆盖（manualWeavingCapacity/manualCoexCapacity）
 * &gt; 工艺库参数（weavingStandardDailyOutput/coexMaxDailyOutput）
 * &gt; 抛 MISSING_CAPACITY 熔断异常。</p>
 *
 * <p>异常消息格式 {@code MISSING_CAPACITY:<成品零件号>:<带坯零件号>:<缺失字段名>}，
 * 前三段与前端 app.js 既有契约（startsWith 判断 + split(":") 取 parts[1]/parts[2]）
 * 完全兼容，第四段为新增的缺失字段名。</p>
 */
@Component
public class CapacityProvider {

    private final ProductProcessRepo processRepo;

    public CapacityProvider(ProductProcessRepo processRepo) {
        this.processRepo = processRepo;
    }

    /**
     * 一次 findAll 构建产能快照（每次排产/询单请求调用一次，快照内 O(1) 取值）。
     * 不长期缓存，保证 POST /process/save 更新工艺库后下次请求即生效。
     */
    public CapacitySnapshot loadSnapshot() {
        Map<String, BigDecimal> weavingByTapePn = new HashMap<>();
        Map<String, BigDecimal> coexByFinishedPn = new HashMap<>();
        for (ProductProcess p : processRepo.findAll()) {
            // 忽略空值条目；多个成品共用同一带坯时取首个非空标准日产
            if (p.getTapePartNumber() != null && p.getWeavingStandardDailyOutput() != null) {
                weavingByTapePn.putIfAbsent(p.getTapePartNumber(), p.getWeavingStandardDailyOutput());
            }
            if (p.getFinishedPartNumber() != null && p.getCoexMaxDailyOutput() != null) {
                coexByFinishedPn.putIfAbsent(p.getFinishedPartNumber(), p.getCoexMaxDailyOutput());
            }
        }
        return new CapacitySnapshot(weavingByTapePn, coexByFinishedPn);
    }

    /**
     * 工艺库产能快照：持有 tapePartNumber→织造标准日产 与
     * finishedPartNumber→共挤最大日产 两个 O(1) 查找 Map。
     */
    public static class CapacitySnapshot {

        private final Map<String, BigDecimal> weavingByTapePn;
        private final Map<String, BigDecimal> coexByFinishedPn;

        CapacitySnapshot(Map<String, BigDecimal> weavingByTapePn, Map<String, BigDecimal> coexByFinishedPn) {
            this.weavingByTapePn = weavingByTapePn;
            this.coexByFinishedPn = coexByFinishedPn;
        }

        /**
         * 解析织造日产能：人工覆盖 > 工艺库织造标准日产 > 抛 MISSING_CAPACITY。
         */
        public BigDecimal resolveWeavingCapacity(String tapePartNumber, String finishedPartNumber,
                                                 BigDecimal manualOverride) {
            if (manualOverride != null && manualOverride.compareTo(BigDecimal.ZERO) > 0) {
                assertPositive(manualOverride, tapePartNumber);
                return manualOverride;
            }
            BigDecimal std = weavingByTapePn.get(tapePartNumber);
            if (std != null && std.compareTo(BigDecimal.ZERO) > 0) {
                return std;
            }
            throw missingCapacity(finishedPartNumber, tapePartNumber, "weavingStandardDailyOutput");
        }

        /**
         * 解析共挤日产能：人工覆盖 > 工艺库共挤最大日产 > 抛 MISSING_CAPACITY。
         */
        public BigDecimal resolveCoexCapacity(String finishedPartNumber, String tapePartNumber,
                                              BigDecimal manualOverride) {
            if (manualOverride != null && manualOverride.compareTo(BigDecimal.ZERO) > 0) {
                assertPositive(manualOverride, finishedPartNumber);
                return manualOverride;
            }
            BigDecimal max = coexByFinishedPn.get(finishedPartNumber);
            if (max != null && max.compareTo(BigDecimal.ZERO) > 0) {
                return max;
            }
            throw missingCapacity(finishedPartNumber, tapePartNumber, "coexMaxDailyOutput");
        }

        /**
         * 出口断言：确保产能值 > 0，防止下游除零异常。
         */
        private static void assertPositive(BigDecimal value, String context) {
            if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("产能值必须大于0，请检查工艺库配置：" + context);
            }
        }

        private static RuntimeException missingCapacity(String finishedPartNumber, String tapePartNumber,
                                                        String missingField) {
            return new RuntimeException("MISSING_CAPACITY:" + finishedPartNumber + ":" + tapePartNumber + ":" + missingField);
        }
    }
}
