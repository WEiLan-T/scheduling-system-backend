package com.company.scheduling.service;

import com.company.scheduling.domain.InventoryReconciliation;
import com.company.scheduling.domain.ProductProcess;
import com.company.scheduling.domain.VirtualWarehouse;
import com.company.scheduling.dto.DataQualityReport;
import com.company.scheduling.dto.ImportResult;
import com.company.scheduling.dto.InventoryReconciliationDTO;
import com.company.scheduling.repository.InventoryReconciliationRepo;
import com.company.scheduling.repository.ProductProcessRepo;
import com.company.scheduling.repository.VirtualWarehouseRepo;
import com.company.scheduling.util.ExcelUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 库存 Excel 导入与核对服务
 * 支持表头偏移自动定位（如第3行表头），带坯编号斜杠拆分由清洗器完成
 */
@Service
public class InventoryImportService {

    private static final Logger log = LoggerFactory.getLogger(InventoryImportService.class);

    private static final int BATCH_SIZE = 1000;

    /** 动态月份数量列表头正则，如"2026年7月数量（米）"（不得硬编码月份） */
    private static final Pattern MONTH_QTY_COL_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月数量");

    @Autowired
    private PythonDataCleaner pythonDataCleaner;

    @Autowired
    private VirtualWarehouseRepo warehouseRepo;

    @Autowired
    private InventoryReconciliationRepo reconciliationRepo;

    @Autowired
    private ProductProcessRepo processRepo;

    /**
     * 导入库存Excel数据
     * 流程: 定位表头(支持第3行表头) → 动态解析月份列/机台列 → Python清洗(斜杠拆分/必填校验)
     *      → 增量识别(partNumber+tapeCode+snapshotDate) → 入库 → 生成核对记录
     * 动态月份列：一行多月份有值时生成多条快照记录（snapshotDate=该月最后一天）；
     * 未识别到月份列时退回调用方传入的 snapshotDate。
     *
     * @param file         Excel文件
     * @param snapshotDate 快照日期（如2026-07-31，仅在无月份列时作为默认值）
     * @return ImportResult 含导入批次ID
     */
    @Transactional
    public ImportResult importInventoryExcel(MultipartFile file, LocalDate snapshotDate) {
        if (file == null || file.isEmpty()) throw new RuntimeException("文件为空！");
        if (snapshotDate == null) throw new RuntimeException("快照日期不能为空！");

        // 1+2. 定位表头并读取数据（同时保留型号/经线/纬线等附加列信息，并收集同零件号型号规格冲突）
        List<Map<String, String>> rows = new ArrayList<>();
        Map<String, String[]> extrasByPartNumber = new HashMap<>();
        Set<String> conflictedPartNumbers = new HashSet<>();
        readInventoryRows(file, rows, extrasByPartNumber, conflictedPartNumbers);

        // 3. Python清洗（库存规则：斜杠拆分、必填校验；不可用时降级到Java内置清洗）
        DataQualityReport report = pythonDataCleaner.cleanInventoryData(rows);

        // 导入冲突检测：同零件号多行型号规格不一致时记入质量报告警告，并改用工艺库值
        Map<String, ProductProcess> processByTapePn = loadProcessByTapePartNumber();
        for (String pn : conflictedPartNumbers) {
            ProductProcess proc = processByTapePn.get(pn);
            String procSpec = proc != null ? proc.getTapeModelSpec() : null;
            String[] extras = extrasByPartNumber.get(pn);
            if (extras != null) extras[0] = procSpec; // 保留工艺库值
            report.getGradeBDetails().add("⚠️ 零件号 [" + pn + "] 存在多行型号规格不一致，已改用工艺库值: "
                    + (procSpec != null && !procSpec.isEmpty() ? procSpec : "(工艺库未维护)"));
        }

        String batchId = "INV-" + snapshotDate + "-" + UUID.randomUUID().toString().substring(0, 8);
        int inserted = 0;
        int skipped = 0;
        Set<String> seenKeys = new HashSet<>();
        List<VirtualWarehouse> warehouseBuffer = new ArrayList<>(BATCH_SIZE);
        List<InventoryReconciliation> reconcileBuffer = new ArrayList<>(BATCH_SIZE);

        // 4+5. 增量识别（基于 partNumber + tapeCode + snapshotDate）并保存库存记录
        // 批量预载已有唯一键集合，内存判重替代逐行DB查重
        Set<String> existingKeys = new HashSet<>(warehouseRepo.findAllExistingKeys());

        // 预扫一遍取记录中的最大快照日期，再批量加载该日期之前的历史快照值（替代逐行 findPreviousStockValue 查询）
        LocalDate maxSnapshotDate = snapshotDate;
        for (Map<String, Object> record : report.getCleanedData()) {
            LocalDate recDate = parseSnapshotDate(str(record, "snapshotDate"));
            if (recDate != null && recDate.isAfter(maxSnapshotDate)) maxSnapshotDate = recDate;
        }
        Map<String, TreeMap<LocalDate, BigDecimal>> stockHistory = loadStockHistoryBefore(maxSnapshotDate);

        for (Map<String, Object> record : report.getCleanedData()) {
            String partNumber = str(record, "partNumber");
            String tapeCode = str(record, "tapeCode", "beltNo");
            BigDecimal quantity = toBigDecimal(firstNonNull(record.get("stockMeters"), record.get("quantity")));
            // 动态月份列携带的快照日期（该月最后一天），无则退回调用方传入的 snapshotDate
            LocalDate recSnapshotDate = parseSnapshotDate(str(record, "snapshotDate"));
            if (recSnapshotDate == null) recSnapshotDate = snapshotDate;
            // 机台非空 = 在产未落库，machineNo 落库
            String machineNo = str(record, "machineNo");
            if (partNumber == null) {
                skipped++;
                continue;
            }
            if (tapeCode == null) tapeCode = "DEFAULT";
            if (quantity == null) quantity = BigDecimal.ZERO;

            String uniqueKey = partNumber + "|" + tapeCode + "|" + recSnapshotDate;
            if (!seenKeys.add(uniqueKey)) {
                skipped++;
                continue;
            }
            // 内存判重（预载唯一键集合，替代逐行DB查重）
            if (existingKeys.contains(uniqueKey)) {
                skipped++;
                continue;
            }

            String classifiedRemark = str(record, "remark");
            String[] extras = extrasByPartNumber.get(partNumber);
            // modelSpec 为空的行按零件号反查工艺库补齐（含冲突检测后工艺库无值时的兜底）
            String modelSpec = extras != null ? extras[0] : null;
            if (modelSpec == null || modelSpec.isEmpty()) {
                ProductProcess proc = processByTapePn.get(partNumber);
                if (proc != null) modelSpec = proc.getTapeModelSpec();
            }

            VirtualWarehouse warehouse = new VirtualWarehouse();
            warehouse.setPartNumber(partNumber);
            warehouse.setTapeCode(tapeCode);
            warehouse.setModelSpec(modelSpec);
            warehouse.setWarpThread(extras != null ? extras[1] : null);
            warehouse.setWeftThread(extras != null ? extras[2] : null);
            warehouse.setStockMeters(quantity);
            warehouse.setStockType(classifiedRemark != null ? classifiedRemark : "库存");
            warehouse.setMachineNo(machineNo);
            warehouse.setRemark(extras != null && extras[3] != null && !extras[3].isEmpty() ? extras[3] : classifiedRemark);
            warehouse.setSnapshotDate(recSnapshotDate);
            warehouse.setReconcileStatus("PENDING");
            warehouse.setDataQualityFlag("A");
            warehouseBuffer.add(warehouse);
            existingKeys.add(uniqueKey);

            // 生成核对记录：Excel值 vs DB计算值（上一期快照值，无则为0，批量查询）
            BigDecimal dbCalculated = findPreviousStockValue(partNumber, tapeCode, recSnapshotDate, stockHistory);
            // 当前记录回填历史树，同批次较早月份的记录可作为后续月份的上一期值
            stockHistory.computeIfAbsent(partNumber + "|" + tapeCode, k -> new TreeMap<>())
                    .put(recSnapshotDate, quantity);
            InventoryReconciliation reconciliation = new InventoryReconciliation();
            reconciliation.setSnapshotDate(recSnapshotDate);
            reconciliation.setPartNumber(partNumber);
            reconciliation.setTapeCode(tapeCode);
            reconciliation.setExcelValue(quantity);
            reconciliation.setDbCalculatedValue(dbCalculated);
            reconciliation.setDifference(quantity.subtract(dbCalculated));
            reconciliation.setReconcileStatus("PENDING");
            reconciliation.setImportBatchId(batchId);
            reconcileBuffer.add(reconciliation);

            inserted++;
            // 6. 分批保存
            if (warehouseBuffer.size() >= BATCH_SIZE) {
                warehouseRepo.saveAll(warehouseBuffer);
                reconciliationRepo.saveAll(reconcileBuffer);
                warehouseRepo.flush();
                warehouseBuffer.clear();
                reconcileBuffer.clear();
            }
        }
        if (!warehouseBuffer.isEmpty()) {
            warehouseRepo.saveAll(warehouseBuffer);
            reconciliationRepo.saveAll(reconcileBuffer);
            warehouseRepo.flush();
        }

        // 7. 返回ImportResult
        ImportResult result = new ImportResult();
        result.setTotalRows(report.getTotalRows());
        result.setInsertedCount(inserted);
        result.setSkippedCount(skipped);
        result.setRejectedCount(report.getGradeCCount());
        result.setQualityReport(report);
        result.setImportBatchId(batchId);
        result.setMessage("📦 库存Excel导入完成：新增 " + inserted + " 条，跳过(重复) " + skipped
                + " 条，拒绝(C级) " + report.getGradeCCount() + " 条，批次号: " + batchId);
        log.info(result.getMessage());
        return result;
    }

    /**
     * 获取库存差值报表
     * 对比Excel人工导入值与DB计算值，返回差值DTO列表
     *
     * @param snapshotDate 快照日期
     */
    public List<InventoryReconciliationDTO> getReconciliationReport(LocalDate snapshotDate) {
        if (snapshotDate == null) {
            // 未指定日期时默认查询最新一期快照
            snapshotDate = reconciliationRepo.findLatestSnapshotDate();
            if (snapshotDate == null) {
                return Collections.emptyList();
            }
        }
        List<InventoryReconciliation> records = reconciliationRepo.findBySnapshotDate(snapshotDate);
        Map<String, VirtualWarehouse> warehouseMap = new HashMap<>();
        for (VirtualWarehouse vw : warehouseRepo.findBySnapshotDate(snapshotDate)) {
            warehouseMap.put(vw.getPartNumber() + "|" + vw.getTapeCode(), vw);
        }

        List<InventoryReconciliationDTO> dtos = new ArrayList<>();
        for (InventoryReconciliation r : records) {
            InventoryReconciliationDTO dto = new InventoryReconciliationDTO();
            dto.setId(r.getId());
            dto.setPartNumber(r.getPartNumber());
            dto.setTapeCode(r.getTapeCode());
            VirtualWarehouse vw = warehouseMap.get(r.getPartNumber() + "|" + r.getTapeCode());
            dto.setModelSpec(vw != null ? vw.getModelSpec() : null);
            dto.setExcelValue(r.getExcelValue());
            dto.setDbCalculatedValue(r.getDbCalculatedValue());
            dto.setDifference(r.getDifference());
            dto.setStatus(r.getReconcileStatus());
            dtos.add(dto);
        }
        return dtos;
    }

    /**
     * 确认核对结果（以人工核对数据为准）
     * 将Excel值设为库存最终值，并将核对记录标记为ACCEPTED
     *
     * @param reconciliationId 核对记录ID
     */
    @Transactional
    public void confirmReconciliation(Long reconciliationId) {
        InventoryReconciliation reconciliation = reconciliationRepo.findById(reconciliationId)
                .orElseThrow(() -> new RuntimeException("核对记录不存在: " + reconciliationId));
        reconciliation.setReconcileStatus("ACCEPTED");
        reconciliationRepo.save(reconciliation);

        // 以Excel人工核对值为准，更新库存快照
        List<VirtualWarehouse> warehouses = warehouseRepo.findByPartNumberAndTapeCodeAndSnapshotDate(
                reconciliation.getPartNumber(), reconciliation.getTapeCode(), reconciliation.getSnapshotDate());
        for (VirtualWarehouse vw : warehouses) {
            vw.setStockMeters(reconciliation.getExcelValue());
            vw.setReconcileStatus("RECONCILED");
            vw.setLastReconcileDate(LocalDate.now());
            warehouseRepo.save(vw);
        }
    }

    // =========================================================================
    // Excel 解析
    // =========================================================================

    /**
     * 定位表头（支持第3行表头偏移）并按清洗器要求的位置键(col0..col5)读取数据行
     * 位置约定: 0零件号|1带坯编号|2数量|3备注|4快照日期(月份列透传)|5机台
     * 动态月份列：正则识别形如"2026年7月数量（米）"的表头，一行多月份有值时拆为多条行记录，
     * 每条携带该月最后一天作为快照日期；同时识别"机台"列（非空=在产未落库）。
     * 同时将 型号规格/经线/纬线/原始备注 按零件号缓存，供实体映射使用；
     * 同零件号型号规格不一致时记入 conflictedPartNumbers，由调用方落库前改用工艺库值
     */
    private void readInventoryRows(MultipartFile file, List<Map<String, String>> rows,
                                   Map<String, String[]> extrasByPartNumber, Set<String> conflictedPartNumbers) {
        // 大文件安全：改用磁盘临时文件方式打开，绕过POI流式打开的1亿字节硬上限
        try (Workbook workbook = ExcelUtils.openWorkbookSafely(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            int headerIdx = ExcelUtils.locateHeaderRow(sheet, new String[]{"零件号", "带坯", "数量"}, 6);
            if (headerIdx < 0) headerIdx = 0;
            Row headerRow = sheet.getRow(headerIdx);
            if (headerRow == null) return;

            int colPn = -1, colModel = -1, colWarp = -1, colWeft = -1, colTape = -1;
            int colRemark = -1, colMachine = -1, colFallbackQty = -1;
            // 动态月份列：列索引 -> 该月最后一天（快照日期），保持列序
            Map<Integer, LocalDate> monthQtyCols = new LinkedHashMap<>();

            for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                String h = ExcelUtils.getCellStringValue(headerRow.getCell(j)).trim().replaceAll("\\s+", "");
                if (h.isEmpty()) continue;
                // 月份列优先于通用"数量"列识别，避免被后者吞并
                Matcher matcher = MONTH_QTY_COL_PATTERN.matcher(h);
                if (matcher.find()) {
                    YearMonth ym = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                    monthQtyCols.put(j, ym.atEndOfMonth());
                    continue;
                }
                if (h.contains("零件号") || h.equals("零件")) colPn = j;
                // 防御加固：经线/纬线判断前置，防止"纬线规格"类表头被"型号/规格"分支误判为型号规格列
                else if (h.contains("经线")) colWarp = j;
                else if (h.contains("纬线")) colWeft = j;
                else if (h.contains("型号") || h.contains("规格")) colModel = j;
                else if (h.contains("带坯")) colTape = j;
                else if (h.contains("机台")) colMachine = j;
                else if (h.contains("数量") || h.contains("库存")) colFallbackQty = j;
                else if (h.contains("备注")) colRemark = j;
            }

            // 旧格式兜底：未识别到月份列时退回通用数量列（快照日期由调用方传入，col4置空）
            if (monthQtyCols.isEmpty() && colFallbackQty >= 0) {
                monthQtyCols.put(colFallbackQty, null);
            }

            for (int i = headerIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String partNumber = colPn >= 0 ? ExcelUtils.getCellStringValue(row.getCell(colPn)) : "";
                String tapeCode = colTape >= 0 ? ExcelUtils.getCellStringValue(row.getCell(colTape)) : "";
                String remark = colRemark >= 0 ? ExcelUtils.getCellStringValue(row.getCell(colRemark)) : "";
                String machineNo = colMachine >= 0 ? ExcelUtils.getCellStringValue(row.getCell(colMachine)) : "";
                if (partNumber.isEmpty() && tapeCode.isEmpty()) continue;

                // 一行多月份有值 → 生成多条快照记录（snapshotDate = 该月最后一天）
                for (Map.Entry<Integer, LocalDate> col : monthQtyCols.entrySet()) {
                    String qty = ExcelUtils.getCellStringValue(row.getCell(col.getKey()));
                    boolean fallbackCol = col.getValue() == null;
                    if (qty.isEmpty() && !fallbackCol) continue;
                    rows.add(buildRowMap(partNumber, tapeCode, qty, remark,
                            col.getValue() != null ? col.getValue().toString() : "", machineNo));
                }

                if (!partNumber.isEmpty()) {
                    String modelSpec = colModel >= 0 ? ExcelUtils.getCellStringValue(row.getCell(colModel)) : "";
                    String warp = colWarp >= 0 ? ExcelUtils.getCellStringValue(row.getCell(colWarp)) : "";
                    String weft = colWeft >= 0 ? ExcelUtils.getCellStringValue(row.getCell(colWeft)) : "";
                    String specValue = modelSpec.isEmpty() ? null : modelSpec;
                    String[] existing = extrasByPartNumber.get(partNumber);
                    if (existing == null) {
                        extrasByPartNumber.put(partNumber, new String[]{
                                specValue,
                                warp.isEmpty() ? null : warp,
                                weft.isEmpty() ? null : weft,
                                remark});
                    } else if (specValue != null && existing[0] != null && !specValue.equals(existing[0])) {
                        // 冲突检测：同零件号型号规格不一致，记录后由调用方改用工艺库值
                        conflictedPartNumbers.add(partNumber);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("库存Excel解析失败: " + e.getMessage(), e);
        }
    }

    /** 构造清洗器行记录：col0..col3 为原有字段，col4/col5 透传快照日期与机台 */
    private Map<String, String> buildRowMap(String partNumber, String tapeCode, String qty,
                                            String remark, String recSnapshotDate, String machineNo) {
        Map<String, String> rowMap = new HashMap<>();
        rowMap.put("col0", partNumber);
        rowMap.put("col1", tapeCode);
        rowMap.put("col2", qty);
        rowMap.put("col3", remark);
        rowMap.put("col4", recSnapshotDate);
        rowMap.put("col5", machineNo);
        return rowMap;
    }

    /** 按带坯零件号反查工艺库构建缓存 Map（一次 findAll，替代逐行查询；同带坯零件号取首条） */
    private Map<String, ProductProcess> loadProcessByTapePartNumber() {
        Map<String, ProductProcess> byTapePn = new HashMap<>();
        for (ProductProcess p : processRepo.findAll()) {
            if (p.getTapePartNumber() != null) byTapePn.putIfAbsent(p.getTapePartNumber(), p);
        }
        return byTapePn;
    }

    /**
     * 批量加载指定日期之前的全部历史快照值，按 零件号|带坯编号 分组并按日期排序，
     * 供推算上一期快照值时内存查找（替代逐行DB查询）
     */
    private Map<String, TreeMap<LocalDate, BigDecimal>> loadStockHistoryBefore(LocalDate date) {
        Map<String, TreeMap<LocalDate, BigDecimal>> history = new HashMap<>();
        for (Object[] row : warehouseRepo.findStockBefore(date)) {
            if (row == null || row.length < 4 || row[0] == null || row[2] == null) continue;
            String key = row[0].toString() + "|" + (row[1] != null ? row[1].toString() : "DEFAULT");
            BigDecimal stock = row[3] != null ? new BigDecimal(row[3].toString()) : BigDecimal.ZERO;
            history.computeIfAbsent(key, k -> new TreeMap<>()).put((LocalDate) row[2], stock);
        }
        return history;
    }

    /**
     * 从预载的历史快照树中查找上一期快照值作为DB计算基准（不存在返回0）
     */
    private BigDecimal findPreviousStockValue(String partNumber, String tapeCode, LocalDate snapshotDate,
                                              Map<String, TreeMap<LocalDate, BigDecimal>> stockHistory) {
        TreeMap<LocalDate, BigDecimal> history = stockHistory.get(partNumber + "|" + tapeCode);
        if (history == null) return BigDecimal.ZERO;
        Map.Entry<LocalDate, BigDecimal> prev = history.lowerEntry(snapshotDate);
        return prev != null && prev.getValue() != null ? prev.getValue() : BigDecimal.ZERO;
    }

    /** 解析清洗记录透传的快照日期（yyyy-MM-dd），非法或空返回null */
    private LocalDate parseSnapshotDate(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // 类型转换辅助
    // =========================================================================

    private String str(Map<String, Object> record, String... keys) {
        for (String key : keys) {
            Object v = record.get(key);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        return ExcelUtils.parseBigDecimalSafely(v.toString());
    }
}
