package com.company.scheduling.repository;

import com.company.scheduling.domain.ProductProcess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ProductProcessRepo extends JpaRepository<ProductProcess, Integer> {
    // 排产核心：根据销售订单中的成品零件号，精准反查工艺BOM与经纬线配置
    Optional<ProductProcess> findByFinishedPartNumber(String finishedPartNumber);

    /**
     * 分页/搜索/筛选查询（供 list 端点可选分页使用）。
     * keyword 模糊匹配：成品零件号/带坯零件号/成品规格型号；筛选列：材料类型。
     * 所有参数均可为 null（=不过滤）。
     * 注：运行库存在历史遗留 bytea 列，JPQL LOWER(bytea) 无对应函数会报 400，
     * 故改用 native 查询并对所有文本列 CAST(col AS text)，ILIKE 保持大小写不敏感语义；
     * 排序内联在 SQL 中（调用方传无排序 Pageable），countQuery 显式提供避免派生歧义。
     */
    @Query(value = "SELECT * FROM product_process p WHERE " +
            "(:keyword IS NULL OR CAST(p.finished_part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(p.tape_part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(p.finished_model_spec AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:materialType IS NULL OR CAST(p.material_type AS text) = CAST(:materialType AS text)) " +
            "ORDER BY p.id ASC",
           countQuery = "SELECT COUNT(*) FROM product_process p WHERE " +
            "(:keyword IS NULL OR CAST(p.finished_part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(p.tape_part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(p.finished_model_spec AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:materialType IS NULL OR CAST(p.material_type AS text) = CAST(:materialType AS text))",
           nativeQuery = true)
    Page<ProductProcess> search(@Param("keyword") String keyword,
                                @Param("materialType") String materialType,
                                Pageable pageable);
}