package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.*;
import com.company.scheduling.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        this.weavingLogRepo = weavingLogRepo;
        this.weavingStatusRepo = weavingStatusRepo;
        this.coexLogRepo = coexLogRepo;
        this.coexStatusRepo = coexStatusRepo;
        this.warehouseRepo = warehouseRepo;
    }

    // ==========================================
    // 🧶 织造车间：状态更新 + 台账记录 + 库存增量同步
    // ==========================================
    @Transactional
    public String recordWeavingData(WeavingEntryRequest req, String currentUser) {
        // 1. 拆解并更新机台状态
        WeavingMachineStatus status = weavingStatusRepo.findById(req.getMachineId()).orElse(new WeavingMachineStatus());
        status.setMachineId(req.getMachineId());
        status.setWorkshopId(req.getWorkshopId());
        status.setWarpSpec(req.getWarpSpec());
        status.setWeftSpec(req.getWeftSpec());
        status.setBobbinCount(req.getBobbinCount());
        status.setMachineStatus(req.getMachineStatus());
        status.setCaliberLimit(req.getCaliberLimit());
        status.setAdjacentMachine(req.getAdjacentMachine());
        status.setOperatorName(req.getOperatorName());
        status.setEnteredBy(currentUser);
        weavingStatusRepo.save(status);

        // 2. 拆解并写入织造台账
        WeavingDailyLog log = new WeavingDailyLog();
        log.setTapePartNumber(req.getTapePartNumber());
        log.setMachineId(req.getMachineId());
        log.setCapacityPerDay(req.getCapacityPerDay());
        log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks());
        log.setEntryDate(req.getEntryDate());
        log.setTotalDemand(req.getTotalDemand());
        log.setEnteredBy(currentUser);
        weavingLogRepo.save(log);

        // 3. 自动同步虚拟库存 (正数：增加库存)
        updateVirtualWarehouse(req.getTapePartNumber(), req.getCapacityPerDay(), req.getEntryDate(), currentUser);

        return "✅ 织造数据录入成功！机台状态更新，带坯库存已自动增加。";
    }

    // ==========================================
    // 🗜️ 共挤车间：状态更新 + 台账记录 + 库存扣减同步
    // ==========================================
    @Transactional
    public String recordCoexData(CoexEntryRequest req, String currentUser) {
        // 1. 拆解并更新产线状态
        CoexLineStatus status = coexStatusRepo.findById(req.getLineId()).orElse(new CoexLineStatus());
        status.setLineId(req.getLineId());
        status.setWorkshopId(req.getWorkshopId());
        status.setCaliberLimit(req.getCaliberLimit());
        status.setLineStatus(req.getLineStatus());
        status.setEnteredBy(currentUser);
        coexStatusRepo.save(status);

        // 2. 拆解并写入共挤台账
        CoexDailyLog log = new CoexDailyLog();
        log.setFinishedPartNumber(req.getFinishedPartNumber());
        log.setLineId(req.getLineId());
        log.setCapacityPerDay(req.getCapacityPerDay());
        log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks());
        log.setTapeDemandQty(req.getTapeDemandQty());
        log.setTapePartNumber(req.getTapePartNumber());
        log.setEntryDate(req.getEntryDate());
        log.setEnteredBy(currentUser);
        coexLogRepo.save(log);

        // 3. 自动同步虚拟库存 (负数：扣减库存)
        if (req.getTapeDemandQty() != null && req.getTapeDemandQty().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(req.getTapePartNumber(), req.getTapeDemandQty().negate(), req.getEntryDate(), currentUser);
        }

        return "✅ 共挤数据录入成功！产线状态更新，消耗带坯已自动扣除。";
    }

    // ==========================================
    // 📦 虚拟库存：人工极速调账干预 (历史遗留接口)
    // ==========================================
    @Transactional
    public String manualAdjustInventory(InventoryAdjustRequest req, String currentUser) {
        updateVirtualWarehouse(req.getTapePartNumber(), req.getAdjustMeters(), req.getEntryDate(), currentUser);
        return "✅ 虚拟仓库数据人工调账成功！";
    }

    // --- 内部封装：核心库存加减处理器 ---
    private void updateVirtualWarehouse(String tapePartNumber, BigDecimal changeMeters, LocalDate entryDate, String currentUser) {
        if (tapePartNumber == null || tapePartNumber.isEmpty() || changeMeters == null) return;

        VirtualWarehouse warehouse = warehouseRepo.findByTapePartNumber(tapePartNumber).orElse(new VirtualWarehouse());
        warehouse.setTapePartNumber(tapePartNumber);

        BigDecimal current = warehouse.getCurrentStockMeters() != null ? warehouse.getCurrentStockMeters() : BigDecimal.ZERO;
        warehouse.setCurrentStockMeters(current.add(changeMeters));

        warehouse.setEntryDate(entryDate);
        warehouse.setEnteredBy(currentUser);
        warehouseRepo.save(warehouse);
    }

    // ==========================================
    // 🌟 全新升级：虚拟库存大盘完整 CRUD 控制台
    // ==========================================

    // 1. 列表查询与带坯模糊检索
    public List<VirtualWarehouse> searchInventory(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return warehouseRepo.findAll();
        }
        return warehouseRepo.findByTapePartNumberContainingIgnoreCase(keyword);
    }

    // 2. 新增或修改库存数据卡片
    @Transactional
    public String saveOrUpdateInventory(VirtualWarehouse inv, String currentUser) {
        if (inv.getId() != null) {
            VirtualWarehouse existing = warehouseRepo.findById(inv.getId()).orElse(new VirtualWarehouse());
            existing.setTapePartNumber(inv.getTapePartNumber());
            existing.setFinishedPartNumber(inv.getFinishedPartNumber());
            existing.setCurrentStockMeters(inv.getCurrentStockMeters());
            existing.setEntryDate(inv.getEntryDate() != null ? inv.getEntryDate() : LocalDate.now());
            existing.setEnteredBy(currentUser);
            warehouseRepo.save(existing);
            return "📦 库存档案信息修正成功！";
        } else {
            inv.setEnteredBy(currentUser);
            if(inv.getEntryDate() == null) inv.setEntryDate(LocalDate.now());
            warehouseRepo.save(inv);
            return "📦 成功建立新的库存档案卡片！";
        }
    }

    // 3. 物理销毁库存数据
    @Transactional
    public String deleteInventory(Integer id) {
        warehouseRepo.deleteById(id);
        return "⚠️ 数据条目已从物理磁盘永久销毁！";
    }
}