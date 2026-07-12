package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.*;
import com.company.scheduling.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

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

    @Transactional
    public String manualAdjustInventory(InventoryAdjustRequest req, String currentUser) {
        updateVirtualWarehouse(req.getTapePartNumber(), req.getAdjustMeters(), req.getEntryDate(), currentUser);
        return "✅ 虚拟仓库数据人工调账成功！";
    }

    // 内部方法：抽象化的库存加减处理器
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
}