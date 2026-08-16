package com.company.scheduling.repository;

import com.company.scheduling.domain.InventoryReconciliation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface InventoryReconciliationRepo extends JpaRepository<InventoryReconciliation, Long> {
    List<InventoryReconciliation> findByImportBatchId(String importBatchId);
    List<InventoryReconciliation> findByReconcileStatus(String status);
    List<InventoryReconciliation> findBySnapshotDate(LocalDate snapshotDate);

    // 查询最新快照日期（报表未指定日期时使用）
    @Query("SELECT MAX(r.snapshotDate) FROM InventoryReconciliation r")
    LocalDate findLatestSnapshotDate();
}
