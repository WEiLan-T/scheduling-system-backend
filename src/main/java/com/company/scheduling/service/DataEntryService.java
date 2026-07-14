package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.*;
import com.company.scheduling.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class DataEntryService {

    private final WeavingDailyLogRepo weavingLogRepo;
    private final WeavingMachineStatusRepo weavingStatusRepo;
    private final CoexDailyLogRepo coexLogRepo;
    private final CoexLineStatusRepo coexStatusRepo;
    private final VirtualWarehouseRepo warehouseRepo;

    public DataEntryService(WeavingDailyLogRepo weavingLogRepo, WeavingMachineStatusRepo weavingStatusRepo,
                            CoexDailyLogRepo coexLogRepo, CoexLineStatusRepo coexStatusRepo,
                            VirtualWarehouseRepo warehouseRepo) {
        this.weavingLogRepo = weavingLogRepo; this.weavingStatusRepo = weavingStatusRepo;
        this.coexLogRepo = coexLogRepo; this.coexStatusRepo = coexStatusRepo; this.warehouseRepo = warehouseRepo;
    }

    public List<WeavingMachineStatus> getAllWeavingMachines() { return weavingStatusRepo.findAll(); }
    public List<CoexLineStatus> getAllCoexLines() { return coexStatusRepo.findAll(); }
    public List<WeavingDailyLog> getWeavingLogs() { return weavingLogRepo.findAllByOrderByEntryDateDesc(); }
    public List<CoexDailyLog> getCoexLogs() { return coexLogRepo.findAllByOrderByEntryDateDesc(); }

    // ==========================================
    // 🧶 织造车间：增改逻辑与【型号+编号】双重库存平补
    // ==========================================
    @Transactional
    public String recordWeavingData(WeavingEntryRequest req, String currentUser) {
        WeavingMachineStatus status = weavingStatusRepo.findById(req.getMachineId()).orElse(new WeavingMachineStatus());
        status.setMachineId(req.getMachineId()); status.setWorkshopId(req.getWorkshopId()); status.setWarpSpec(req.getWarpSpec());
        status.setWeftSpec(req.getWeftSpec()); status.setBobbinCount(req.getBobbinCount()); status.setMachineStatus(req.getMachineStatus());
        status.setCaliberLimit(req.getCaliberLimit()); status.setAdjacentMachine(req.getAdjacentMachine());
        status.setOperatorName(req.getOperatorName()); status.setEnteredBy(currentUser);
        weavingStatusRepo.save(status);

        WeavingDailyLog log;
        if (req.getId() != null) {
            log = weavingLogRepo.findById(req.getId()).orElseThrow(() -> new RuntimeException("找不到对应要修改的织造记录！"));

            // 🌟 核心修复 1：防止旧数据为 null 导致的比对崩溃
            String oldTapePn = log.getTapePartNumber() == null ? "" : log.getTapePartNumber();
            String oldTapeNum = log.getTapeNumber() == null ? "DEFAULT" : log.getTapeNumber();
            String newTapePn = req.getTapePartNumber() == null ? "" : req.getTapePartNumber();
            String newTapeNum = req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty() ? "DEFAULT" : req.getTapeNumber().trim();

            BigDecimal oldCap = log.getCapacityPerDay() != null ? log.getCapacityPerDay() : BigDecimal.ZERO;
            BigDecimal newCap = req.getCapacityPerDay() != null ? req.getCapacityPerDay() : BigDecimal.ZERO;

            if (!newTapePn.equals(oldTapePn) || !newTapeNum.equals(oldTapeNum)) {
                updateVirtualWarehouse(oldTapePn, oldTapeNum, oldCap.negate(), req.getEntryDate(), currentUser);
                updateVirtualWarehouse(newTapePn, newTapeNum, newCap, req.getEntryDate(), currentUser);
            } else {
                updateVirtualWarehouse(newTapePn, newTapeNum, newCap.subtract(oldCap), req.getEntryDate(), currentUser);
            }
        } else {
            log = new WeavingDailyLog();
            updateVirtualWarehouse(req.getTapePartNumber(), req.getTapeNumber(), req.getCapacityPerDay(), req.getEntryDate(), currentUser);
        }

        log.setTapePartNumber(req.getTapePartNumber()); log.setTapeNumber(req.getTapeNumber());
        log.setMachineId(req.getMachineId()); log.setCapacityPerDay(req.getCapacityPerDay());
        log.setIsDataNormal(req.getIsDataNormal()); log.setRemarks(req.getRemarks());
        log.setEntryDate(req.getEntryDate()); log.setTotalDemand(req.getTotalDemand()); log.setEnteredBy(currentUser);
        weavingLogRepo.save(log);

        return req.getId() != null ? "✅ 织造历史修改成功，(型号+编号)对应的可用库存已智能重算！" : "✅ 今日织造合并归档落库成功！";
    }

    @Transactional
    public String deleteWeavingLog(Integer id, String currentUser) {
        WeavingDailyLog log = weavingLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getCapacityPerDay() != null && log.getCapacityPerDay().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getTapeNumber(), log.getCapacityPerDay().negate(), LocalDate.now(), currentUser);
        }
        weavingLogRepo.deleteById(id);
        return "🗑️ 织造台账已被物理废弃，对应的带坯库存卡片已扣减还原！";
    }

    // ==========================================
    // 🗜️ 共挤车间：增改逻辑与【型号+编号】双重库存平补
    // ==========================================
    @Transactional
    public String recordCoexData(CoexEntryRequest req, String currentUser) {
        // 如果前端未传递带坯型号，则系统自动根据卷号去库存中寻找
        if (req.getTapePartNumber() == null || req.getTapePartNumber().trim().isEmpty()) {
            if (req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty()) {
                throw new RuntimeException("操作失败：必须提供消耗的带坯物理编号（卷号）！");
            }

            // 根据物理卷号去库存档案寻找
            VirtualWarehouse vw = warehouseRepo.findFirstByTapeNumber(req.getTapeNumber().trim())
                    .orElseThrow(() -> new RuntimeException("系统拦截：当前库存中找不到编号为 [" + req.getTapeNumber() + "] 的带坯卷，无法自动识别型号，请核对该卷号是否已入库！"));

            // 自动为请求对象补全带坯零件号，使接下来的库存平补逻辑正常运行
            req.setTapePartNumber(vw.getTapePartNumber());
        }
        CoexLineStatus status = coexStatusRepo.findById(req.getLineId()).orElse(new CoexLineStatus());
        status.setLineId(req.getLineId()); status.setWorkshopId(req.getWorkshopId());
        status.setCaliberLimit(req.getCaliberLimit()); status.setLineStatus(req.getLineStatus()); status.setEnteredBy(currentUser);
        coexStatusRepo.save(status);

        CoexDailyLog log;
        if (req.getId() != null) {
            log = coexLogRepo.findById(req.getId()).orElseThrow(() -> new RuntimeException("找不到对应要修改的共挤记录！"));

            // 🌟 核心修复 1：防空指针
            String oldTapePn = log.getTapePartNumber() == null ? "" : log.getTapePartNumber();
            String oldTapeNum = log.getTapeNumber() == null ? "DEFAULT" : log.getTapeNumber();
            String newTapePn = req.getTapePartNumber() == null ? "" : req.getTapePartNumber();
            String newTapeNum = req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty() ? "DEFAULT" : req.getTapeNumber().trim();

            BigDecimal oldDemand = log.getTapeDemandQty() != null ? log.getTapeDemandQty() : BigDecimal.ZERO;
            BigDecimal newDemand = req.getTapeDemandQty() != null ? req.getTapeDemandQty() : BigDecimal.ZERO;

            if (!newTapePn.equals(oldTapePn) || !newTapeNum.equals(oldTapeNum)) {
                updateVirtualWarehouse(oldTapePn, oldTapeNum, oldDemand, req.getEntryDate(), currentUser);
                updateVirtualWarehouse(newTapePn, newTapeNum, newDemand.negate(), req.getEntryDate(), currentUser);
            } else {
                updateVirtualWarehouse(newTapePn, newTapeNum, oldDemand.subtract(newDemand), req.getEntryDate(), currentUser);
            }
        } else {
            log = new CoexDailyLog();
            updateVirtualWarehouse(req.getTapePartNumber(), req.getTapeNumber(), req.getTapeDemandQty().negate(), req.getEntryDate(), currentUser);
        }

        log.setFinishedPartNumber(req.getFinishedPartNumber()); log.setLineId(req.getLineId());
        log.setCapacityPerDay(req.getCapacityPerDay()); log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks()); log.setTapeDemandQty(req.getTapeDemandQty());
        log.setTapePartNumber(req.getTapePartNumber()); log.setTapeNumber(req.getTapeNumber());
        log.setEntryDate(req.getEntryDate()); log.setEnteredBy(currentUser);
        coexLogRepo.save(log);

        return req.getId() != null ? "✅ 共挤台账修改成功，消耗库存已智能对账！" : "✅ 共挤台账归档成功，消耗带坯库存已自动扣除。";
    }

    @Transactional
    public String deleteCoexLog(Integer id, String currentUser) {
        CoexDailyLog log = coexLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getTapeDemandQty() != null && log.getTapeDemandQty().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getTapeNumber(), log.getTapeDemandQty(), LocalDate.now(), currentUser);
        }
        coexLogRepo.deleteById(id);
        return "🗑️ 共挤台账已废弃，已退还扣减的指定编号带坯库存！";
    }

    // ==========================================
    // 📦 智能库存中枢：(型号, 编号) 唯一纽带合并与冲抵
    // ==========================================
    @Transactional
    public String manualAdjustInventory(InventoryAdjustRequest req, String currentUser) {
        if (req.getTapePartNumber() == null || req.getTapePartNumber().trim().isEmpty()) {
            throw new RuntimeException("调账失败：带坯零件号不能为空！");
        }
        if (req.getAdjustMeters() == null) {
            throw new RuntimeException("调账失败：调账米数不能为空！");
        }

        // 调用现有的库存更新逻辑，默认物理卷号为 "DEFAULT"
        updateVirtualWarehouse(
                req.getTapePartNumber().trim(),
                "DEFAULT",
                req.getAdjustMeters(),
                req.getEntryDate() != null ? req.getEntryDate() : LocalDate.now(),
                currentUser
        );

        String action = req.getAdjustMeters().compareTo(BigDecimal.ZERO) >= 0 ? "增加" : "扣减";
        return "📦 手动调账成功！已为带坯 [" + req.getTapePartNumber() + "] (批次: DEFAULT) "
                + action + " " + req.getAdjustMeters().abs() + " 米。";
    }
    private void updateVirtualWarehouse(String tapePartNumber, String tapeNumber, BigDecimal changeMeters, LocalDate entryDate, String currentUser) {
        if (tapePartNumber == null || tapePartNumber.isEmpty() || changeMeters == null) return;
        String finalTapeNum = (tapeNumber == null || tapeNumber.trim().isEmpty()) ? "DEFAULT" : tapeNumber.trim();

        // 🌟 核心：按照 型号 + 物理编号 双重条件进行合并与盘点
        VirtualWarehouse warehouse = warehouseRepo.findByTapePartNumberAndTapeNumber(tapePartNumber, finalTapeNum)
                .orElse(new VirtualWarehouse());

        warehouse.setTapePartNumber(tapePartNumber);
        warehouse.setTapeNumber(finalTapeNum);

        BigDecimal current = warehouse.getCurrentStockMeters() != null ? warehouse.getCurrentStockMeters() : BigDecimal.ZERO;
        warehouse.setCurrentStockMeters(current.add(changeMeters));
        warehouse.setEntryDate(entryDate);
        warehouse.setEnteredBy(currentUser);
        warehouseRepo.save(warehouse);
    }

    public List<VirtualWarehouse> searchInventory(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return warehouseRepo.findAll();
        return warehouseRepo.findByTapePartNumberContainingIgnoreCase(keyword);
    }

    @Transactional
    public String saveOrUpdateInventory(VirtualWarehouse inv, String currentUser) {
        String finalNum = (inv.getTapeNumber() == null || inv.getTapeNumber().trim().isEmpty()) ? "DEFAULT" : inv.getTapeNumber().trim();
        if (inv.getId() != null) {
            VirtualWarehouse existing = warehouseRepo.findById(inv.getId()).orElse(new VirtualWarehouse());
            existing.setTapePartNumber(inv.getTapePartNumber()); existing.setTapeNumber(finalNum);
            existing.setFinishedPartNumber(inv.getFinishedPartNumber()); existing.setCurrentStockMeters(inv.getCurrentStockMeters());
            existing.setEntryDate(inv.getEntryDate() != null ? inv.getEntryDate() : LocalDate.now());
            existing.setEnteredBy(currentUser); warehouseRepo.save(existing);
            return "📦 库存档案信息修正成功！";
        } else {
            // 🌟 手工建档防重，自动合并
            Optional<VirtualWarehouse> existingOpt = warehouseRepo.findByTapePartNumberAndTapeNumber(inv.getTapePartNumber(), finalNum);
            if (existingOpt.isPresent()) {
                VirtualWarehouse target = existingOpt.get();
                target.setCurrentStockMeters(target.getCurrentStockMeters().add(inv.getCurrentStockMeters()));
                target.setEntryDate(inv.getEntryDate() != null ? inv.getEntryDate() : LocalDate.now());
                target.setEnteredBy(currentUser); warehouseRepo.save(target);
                return "📦 发现相同(型号+编号)带坯，库存数量已自动合并追加！";
            } else {
                inv.setTapeNumber(finalNum); inv.setEnteredBy(currentUser);
                if(inv.getEntryDate() == null) inv.setEntryDate(LocalDate.now());
                warehouseRepo.save(inv); return "📦 成功建立全新(型号+编号)库存档案卡片！";
            }
        }
    }

    @Transactional
    public String deleteInventory(Integer id) { warehouseRepo.deleteById(id); return "⚠️ 数据条目已从物理磁盘抹除！"; }

}