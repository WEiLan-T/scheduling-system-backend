package com.company.scheduling.repository;

import com.company.scheduling.domain.CoexDailyLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface CoexDailyLogRepo extends JpaRepository<CoexDailyLog, Long> {

    // 按唯一键组合查询（防重/覆盖更新）
    List<CoexDailyLog> findByLogDateAndMachineNoAndProductModelAndColor(
            LocalDate logDate, String machineNo, String productModel, String color);

    // 查询所有B级（需验证）记录
    @Query("SELECT c FROM CoexDailyLog c WHERE c.dataQualityFlag = 'B'")
    List<CoexDailyLog> findGradeBRecords();

    /** 按产品型号集合定向查询（IN），供排产执行状态等场景替代全表 findAll */
    List<CoexDailyLog> findByProductModelIn(Collection<String> productModels);

    // 判断某产线号是否被台账引用（共挤台账 machine_no 字段直接存产线号；产线档案删除前引用校验）
    boolean existsByMachineNo(String machineNo);

    /**
     * 按带坯零件号聚合共挤消耗（SQL GROUP BY），用于日库存推算：
     * 共挤台账无带坯零件号字段，通过工艺路线（产品型号→成品型号→带坯零件号）关联，
     * 累加账期日在 (from, to] 区间内的产能米数
     */
    @Query("SELECT p.tapePartNumber, SUM(c.capacityMeters) FROM CoexDailyLog c, ProductProcess p " +
            "WHERE c.productModel = p.finishedModelSpec " +
            "AND c.logDate > :from AND c.logDate <= :to " +
            "GROUP BY p.tapePartNumber")
    List<Object[]> sumConsumptionByTapePartNumber(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 分页/搜索/筛选查询（供 list 端点可选分页使用）。
     * keyword 模糊匹配：机台号/产品型号/产品类型；筛选列：机台号/产品型号/颜色。
     * 所有参数均可为 null（=不过滤）。
     * 注：运行库存在历史遗留 bytea 列，JPQL LOWER(bytea) 无对应函数会报 400，
     * 故改用 native 查询并对所有文本列 CAST(col AS text)，ILIKE 保持大小写不敏感语义；
     * 排序内联在 SQL 中（调用方传无排序 Pageable），countQuery 显式提供避免派生歧义。
     */
    @Query(value = "SELECT * FROM coex_daily_log c WHERE " +
            "(:keyword IS NULL OR CAST(c.machine_no AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(c.product_model AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(c.product_type AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:machineNo IS NULL OR CAST(c.machine_no AS text) = CAST(:machineNo AS text)) " +
            "AND (:productModel IS NULL OR CAST(c.product_model AS text) = CAST(:productModel AS text)) " +
            "AND (:color IS NULL OR CAST(c.color AS text) = CAST(:color AS text)) " +
            "ORDER BY c.log_date DESC, c.id DESC",
           countQuery = "SELECT COUNT(*) FROM coex_daily_log c WHERE " +
            "(:keyword IS NULL OR CAST(c.machine_no AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(c.product_model AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(c.product_type AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:machineNo IS NULL OR CAST(c.machine_no AS text) = CAST(:machineNo AS text)) " +
            "AND (:productModel IS NULL OR CAST(c.product_model AS text) = CAST(:productModel AS text)) " +
            "AND (:color IS NULL OR CAST(c.color AS text) = CAST(:color AS text))",
           nativeQuery = true)
    Page<CoexDailyLog> search(@Param("keyword") String keyword,
                              @Param("machineNo") String machineNo,
                              @Param("productModel") String productModel,
                              @Param("color") String color,
                              Pageable pageable);
}
