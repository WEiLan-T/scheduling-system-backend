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
    // 🧶 织造车间：增改逻辑与差量库存同步
    // ==========================================
    public List<WeavingDailyLog> getWeavingLogs() {
        return weavingLogRepo.findAllByOrderByEntryDateDesc();
    }

    @Transactional
    public String recordWeavingData(WeavingEntryRequest req, String currentUser) {
        WeavingMachineStatus status = weavingStatusRepo.findById(req.getMachineId()).orElse(new WeavingMachineStatus());
        status.setMachineId(req.getMachineId()); status.setWorkshopId(req.getWorkshopId()); status.setWarpSpec(req.getWarpSpec());
        status.setWeftSpec(req.getWeftSpec()); status.setBobbinCount(req.getBobbinCount()); status.setMachineStatus(req.getMachineStatus());
        status.setCaliberLimit(req.getCaliberLimit()); status.setAdjacentMachine(req.getAdjacentMachine());
        status.setOperatorName(req.getOperatorName()); status.setEnteredBy(currentUser);
        weavingStatusRepo.save(status);

        WeavingDailyLog log;
        BigDecimal inventoryChange = req.getCapacityPerDay() != null ? req.getCapacityPerDay() : BigDecimal.ZERO;

        // 🌟 核心：如果是修改，算出与旧产能的差值用于平补库存
        if (req.getId() != null) {
            log = weavingLogRepo.findById(req.getId()).orElseThrow(() -> new RuntimeException("找不到要修改的记录！"));
            BigDecimal oldCapacity = log.getCapacityPerDay() != null ? log.getCapacityPerDay() : BigDecimal.ZERO;
            inventoryChange = inventoryChange.subtract(oldCapacity);
        } else {
            log = new WeavingDailyLog();
        }

        log.setTapePartNumber(req.getTapePartNumber()); log.setMachineId(req.getMachineId());
        log.setCapacityPerDay(req.getCapacityPerDay()); log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks()); log.setEntryDate(req.getEntryDate());
        log.setTotalDemand(req.getTotalDemand()); log.setEnteredBy(currentUser);
        weavingLogRepo.save(log);

        if (inventoryChange.compareTo(BigDecimal.ZERO) != 0) {
            updateVirtualWarehouse(req.getTapePartNumber(), inventoryChange, req.getEntryDate(), currentUser);
        }
        return req.getId() != null ? "✅ 织造数据修改成功，库存差额已自动平补！" : "✅ 织造数据录入成功，带坯库存已增加。";
    }

    @Transactional
    public String deleteWeavingLog(Integer id, String currentUser) {
        WeavingDailyLog log = weavingLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getCapacityPerDay() != null && log.getCapacityPerDay().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getCapacityPerDay().negate(), LocalDate.now(), currentUser); // 撤销加库
        }
        weavingLogRepo.deleteById(id);
        return "🗑️ 织造台账已删除，相应的库存增量已同步撤销！";
    }

    // ==========================================
    // 🗜️ 共挤车间：增改逻辑与差量库存同步
    // ==========================================
    public List<CoexDailyLog> getCoexLogs() {
        return coexLogRepo.findAllByOrderByEntryDateDesc();
    }

    @Transactional
    public String recordCoexData(CoexEntryRequest req, String currentUser) {
        CoexLineStatus status = coexStatusRepo.findById(req.getLineId()).orElse(new CoexLineStatus());
        status.setLineId(req.getLineId()); status.setWorkshopId(req.getWorkshopId());
        status.setCaliberLimit(req.getCaliberLimit()); status.setLineStatus(req.getLineStatus()); status.setEnteredBy(currentUser);
        coexStatusRepo.save(status);

        CoexDailyLog log;
        BigDecimal inventoryChange = req.getTapeDemandQty() != null ? req.getTapeDemandQty() : BigDecimal.ZERO;

        if (req.getId() != null) {
            log = coexLogRepo.findById(req.getId()).orElseThrow(() -> new RuntimeException("找不到要修改的记录！"));
            BigDecimal oldDemand = log.getTapeDemandQty() != null ? log.getTapeDemandQty() : BigDecimal.ZERO;
            inventoryChange = inventoryChange.subtract(oldDemand); // 消耗差值
        } else {
            log = new CoexDailyLog();
        }

        log.setFinishedPartNumber(req.getFinishedPartNumber()); log.setLineId(req.getLineId());
        log.setCapacityPerDay(req.getCapacityPerDay()); log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks()); log.setTapeDemandQty(req.getTapeDemandQty());
        log.setTapePartNumber(req.getTapePartNumber()); log.setEntryDate(req.getEntryDate()); log.setEnteredBy(currentUser);
        coexLogRepo.save(log);

        if (inventoryChange.compareTo(BigDecimal.ZERO) != 0) {
            updateVirtualWarehouse(req.getTapePartNumber(), inventoryChange.negate(), req.getEntryDate(), currentUser); // 消耗增加 = 库存扣减
        }
        return req.getId() != null ? "✅ 共挤数据修改成功，库存差额已平补！" : "✅ 共挤数据录入成功，消耗带坯已扣除。";
    }

    @Transactional
    public String deleteCoexLog(Integer id, String currentUser) {
        CoexDailyLog log = coexLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getTapeDemandQty() != null && log.getTapeDemandQty().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getTapeDemandQty(), LocalDate.now(), currentUser); // 撤销减库
        }
        coexLogRepo.deleteById(id);
        return "🗑️ 共挤台账已删除，当初扣减的带坯库存已归还！";
    }

    // ==========================================
    // 📦 虚拟库存调控区 (保持原有逻辑)
    // ==========================================
    @Transactional
    public String manualAdjustInventory(InventoryAdjustRequest req, String currentUser) {
        updateVirtualWarehouse(req.getTapePartNumber(), req.getAdjustMeters(), req.getEntryDate(), currentUser);
        return "✅ 虚拟仓库数据人工调账成功！";
    }

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

    public List<VirtualWarehouse> searchInventory(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return warehouseRepo.findAll();
        return warehouseRepo.findByTapePartNumberContainingIgnoreCase(keyword);
    }

    @Transactional
    public String saveOrUpdateInventory(VirtualWarehouse inv, String currentUser) {
        if (inv.getId() != null) {
            VirtualWarehouse existing = warehouseRepo.findById(inv.getId()).orElse(new VirtualWarehouse());
            existing.setTapePartNumber(inv.getTapePartNumber()); existing.setFinishedPartNumber(inv.getFinishedPartNumber());
            existing.setCurrentStockMeters(inv.getCurrentStockMeters()); existing.setEntryDate(inv.getEntryDate() != null ? inv.getEntryDate() : LocalDate.now());
            existing.setEnteredBy(currentUser); warehouseRepo.save(existing);
            return "📦 库存档案信息修正成功！";
        } else {
            inv.setEnteredBy(currentUser); if(inv.getEntryDate() == null) inv.setEntryDate(LocalDate.now());
            warehouseRepo.save(inv); return "📦 成功建立新的库存档案卡片！";
        }
    }

    @Transactional
    public String deleteInventory(Integer id) {
        warehouseRepo.deleteById(id); return "⚠️ 数据条目已从物理磁盘永久销毁！";
    }
}