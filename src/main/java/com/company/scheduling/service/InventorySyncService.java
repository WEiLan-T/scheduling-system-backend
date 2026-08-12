package com.company.scheduling.service;

import com.company.scheduling.domain.CoexDailyLog;
import com.company.scheduling.domain.ProductProcess;
import com.company.scheduling.domain.WeavingDailyLog;
import com.company.scheduling.repository.ProductProcessRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 虚拟库存异步同步服务（独立Bean）
 * <p>
 * 织造/共挤 Excel 导入事务提交后，由 Controller 层调用本 Bean 的 @Async 方法，
 * 在 importTaskExecutor 线程池中后台批量复用 {@link DataEntryService#updateVirtualWarehouse}
 * 同步虚拟库存快照，导入接口保持同步快速返回。
 * <p>
 * 注意：@Async 方法必须位于独立 Bean（严禁同类自调用，否则绕过代理导致异步失效）。
 * <p>
 * 并发取舍：异步库存同步与手工调账并发时，对同一快照行采用后写覆盖（无行级锁/版本号），
 * 冲突窗口短、概率低，接受此取舍。
 */
@Service
public class InventorySyncService {

    private static final Logger log = LoggerFactory.getLogger(InventorySyncService.class);

    @Autowired
    private DataEntryService dataEntryService;

    @Autowired
    private ProductProcessRepo processRepo;

    /**
     * 织造导入后异步同步库存：每条台账的当班产量为带坯库存增量
     * （方向与手工录入 recordWeavingData 的 +capacityPerDay 语义一致）
     */
    @Async("importTaskExecutor")
    @Transactional
    public void syncWeavingLogsAsync(List<WeavingDailyLog> insertedLogs, String operator) {
        try {
            if (insertedLogs == null || insertedLogs.isEmpty()) return;
            int synced = 0;
            int skipped = 0;
            for (WeavingDailyLog logRow : insertedLogs) {
                // 零件号/产量缺失的行无法同步（updateVirtualWarehouse 对空零件号直接跳过），计数留痕
                if (logRow.getPartNumber() == null || logRow.getPartNumber().isEmpty() || logRow.getShiftOutput() == null) {
                    skipped++;
                    continue;
                }
                dataEntryService.updateVirtualWarehouse(logRow.getPartNumber(), logRow.getTapeCode(),
                        logRow.getShiftOutput(), logRow.getEntryDate(), operator);
                synced++;
            }
            log.info("[库存异步同步] 织造导入同步完成：共 {} 条，同步 {} 条，跳过 {} 条", insertedLogs.size(), synced, skipped);
        } catch (Exception e) {
            // 后台失败仅记日志，不影响已返回的导入结果
            log.error("[库存异步同步] 织造导入后台库存同步失败（{} 条），导入结果不受影响",
                    insertedLogs == null ? 0 : insertedLogs.size(), e);
        }
    }

    /**
     * 共挤导入后异步同步库存：共挤产出消耗带坯，按产量取负扣减
     * （方向与手工录入 recordCoexData 新增时的 negate() 语义一致）。
     * 共挤台账不带零件号，按成品型号(productModel)反查工艺库得到带坯零件号；
     * 反查不到的行跳过并记警告，留待人工核对。
     */
    @Async("importTaskExecutor")
    @Transactional
    public void syncCoexLogsAsync(List<CoexDailyLog> insertedLogs, String operator) {
        try {
            if (insertedLogs == null || insertedLogs.isEmpty()) return;
            // 批次内一次性构建反查缓存，避免逐行 findAll
            Map<String, String> modelToTapePn = loadModelToTapePartNumberMap();
            int synced = 0;
            int skipped = 0;
            int unresolved = 0;
            for (CoexDailyLog logRow : insertedLogs) {
                if (logRow.getCapacityMeters() == null) {
                    skipped++;
                    continue;
                }
                String tapePn = logRow.getProductModel() == null ? null
                        : modelToTapePn.get(logRow.getProductModel().trim());
                if (tapePn == null) {
                    unresolved++;
                    continue;
                }
                // 台账无卷号信息，批次码缺省 DEFAULT（与 updateVirtualWarehouse 空值兜底一致）
                dataEntryService.updateVirtualWarehouse(tapePn, "DEFAULT",
                        logRow.getCapacityMeters().negate(), logRow.getLogDate(), operator);
                synced++;
            }
            log.info("[库存异步同步] 共挤导入同步完成：共 {} 条，同步 {} 条，跳过 {} 条，工艺库未匹配型号 {} 条",
                    insertedLogs.size(), synced, skipped, unresolved);
            if (unresolved > 0) {
                log.warn("[库存异步同步] 共挤导入有 {} 条台账的成品型号在工艺库中反查不到带坯零件号，库存未扣减，请人工核对", unresolved);
            }
        } catch (Exception e) {
            log.error("[库存异步同步] 共挤导入后台库存同步失败（{} 条），导入结果不受影响",
                    insertedLogs == null ? 0 : insertedLogs.size(), e);
        }
    }

    /**
     * 成品规格型号 → 带坯零件号 反查缓存（一次 findAll；同型号取首条，
     * 与 DataEntryService.loadProcessByTapePartNumber 的 putIfAbsent 语义一致）
     */
    private Map<String, String> loadModelToTapePartNumberMap() {
        Map<String, String> byModel = new HashMap<>();
        for (ProductProcess p : processRepo.findAll()) {
            if (p.getFinishedModelSpec() != null && !p.getFinishedModelSpec().trim().isEmpty()
                    && p.getTapePartNumber() != null && !p.getTapePartNumber().trim().isEmpty()) {
                byModel.putIfAbsent(p.getFinishedModelSpec().trim(), p.getTapePartNumber());
            }
        }
        return byModel;
    }
}
