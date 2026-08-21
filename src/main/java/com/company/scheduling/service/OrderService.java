package com.company.scheduling.service;

import com.company.scheduling.domain.ProductionOrder;
import com.company.scheduling.repository.ProductionOrderRepo;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class OrderService {
    private final ProductionOrderRepo orderRepo;

    public OrderService(ProductionOrderRepo orderRepo) { this.orderRepo = orderRepo; }

    public List<ProductionOrder> getAllOrders() { return orderRepo.findAllByOrderByCreatedAtDesc(); }

    /** 分页搜索订单（新增，不影响上方旧全量方法；page 从 0 开始，排序与旧接口一致 createdAt 倒序，已内联在 native SQL 的 ORDER BY 中） */
    public Page<ProductionOrder> searchOrders(int page, int size, String keyword, String orderId, String finishedPartNumber) {
        String kw = (keyword == null || keyword.trim().isEmpty()) ? null : keyword.trim();
        String oid = (orderId == null || orderId.trim().isEmpty()) ? null : orderId.trim();
        String fpn = (finishedPartNumber == null || finishedPartNumber.trim().isEmpty()) ? null : finishedPartNumber.trim();
        return orderRepo.search(kw, oid, fpn, PageRequest.of(page, size));
    }

    @Transactional
    public String saveOrders(List<ProductionOrder> orders, String currentUser) {
        if (orders == null || orders.isEmpty()) return "订单明细不能为空";
        for (ProductionOrder order : orders) {
            order.setEnteredBy(currentUser);
            if(order.getTotalLength() == null && order.getMetersPerRoll() != null && order.getRollCount() != null) {
                order.setTotalLength(order.getMetersPerRoll().multiply(new BigDecimal(order.getRollCount())));
            }
        }
        orderRepo.saveAll(orders);
        return "✅ 订单下达成功！订单号 [" + orders.get(0).getOrderId() + "]，共包含 " + orders.size() + " 行明细。";
    }

    @Transactional
    public String updateOrder(String orderId, List<ProductionOrder> orders, String currentUser) {
        orderRepo.deleteByOrderId(orderId);
        for (ProductionOrder order : orders) {
            order.setEnteredBy(currentUser);
            if(order.getTotalLength() == null && order.getMetersPerRoll() != null && order.getRollCount() != null) {
                order.setTotalLength(order.getMetersPerRoll().multiply(new BigDecimal(order.getRollCount())));
            }
        }
        orderRepo.saveAll(orders);
        return "✅ 订单 [" + orderId + "] 修正更新成功！";
    }

    @Transactional
    public String deleteOrder(String orderId) {
        orderRepo.deleteByOrderId(orderId);
        return "🗑️ 订单 [" + orderId + "] 及其所有明细已从物理磁盘销毁！";
    }

    // ==========================================
    // 🌟 动态表头安全读取引擎
    // ==========================================
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) return cell.getDateCellValue().toString();
                double val = cell.getNumericCellValue();
                if (val == (long) val) return String.valueOf((long) val);
                return String.valueOf(val);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA: try { return cell.getStringCellValue().trim(); } catch (Exception e) { return String.valueOf(cell.getNumericCellValue()); }
            default: return "";
        }
    }

    private BigDecimal parseBigDecimalSafely(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try { return new BigDecimal(str.trim()); } catch (Exception e) { return null; }
    }

    private Integer parseIntegerSafely(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try { return (int) Double.parseDouble(str.trim()); } catch (Exception e) { return null; }
    }

    private LocalDate parseDateSafely(Cell cell) {
        if (cell == null) return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            String str = getCellValueAsString(cell);
            if (!str.isEmpty() && str.contains(" ")) { str = str.split(" ")[0]; }
            return LocalDate.parse(str);
        } catch (Exception e) { return null; }
    }

    @Transactional
    public String importOrderExcel(MultipartFile file, String currentUser) throws Exception {
        if (file == null || file.isEmpty()) throw new RuntimeException("上传的订单 Excel 为空！");
        int successCount = 0; int skipCount = 0;

        Set<String> seenKeys = new HashSet<>(); // 🌟 文件内部去重缓存池

        // 大文件安全：改用磁盘临时文件方式打开，绕过POI流式打开的1亿字节硬上限
        try (Workbook workbook = com.company.scheduling.util.ExcelUtils.openWorkbookSafely(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            // 🌟 动态探测表头列索引
            Row headerRow = sheet.getRow(0);
            int colOrderId = -1, colCustomer = -1, colSales = -1, colUnfinished = -1;
            int colPartNum = -1, colProductName = -1, colModelSpec = -1, colMaterial = -1, colColor = -1;
            int colMetersPerRoll = -1, colRollCount = -1, colTotal = -1;
            int colOrderDate = -1, colDeliveryDate = -1, colRemarks = -1;

            if (headerRow != null) {
                for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                    String header = getCellValueAsString(headerRow.getCell(j)).trim().replaceAll("\\s+", "");
                    if (header.contains("订单号")) colOrderId = j;
                    else if (header.contains("客户名称")) colCustomer = j;
                    else if (header.contains("销售员")) colSales = j;
                    else if (header.contains("未入库")) colUnfinished = j;
                    else if (header.contains("零件号")) colPartNum = j;
                    else if (header.contains("品名")) colProductName = j;
                    else if (header.contains("规格型号")) colModelSpec = j;
                    else if (header.contains("材质")) colMaterial = j;
                    else if (header.contains("胶色")) colColor = j;
                    else if (header.contains("单卷长度") || header.equals("长度")) colMetersPerRoll = j;
                    else if (header.contains("卷数") || header.equals("卷")) colRollCount = j;
                    else if (header.contains("总数量") || header.equals("数量")) colTotal = j;
                    else if (header.contains("下达时间")) colOrderDate = j;
                    else if (header.contains("交货期")) colDeliveryDate = j;
                    else if (header.contains("备注")) colRemarks = j;
                }
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i); if (row == null) continue;

                String orderId = colOrderId >= 0 ? getCellValueAsString(row.getCell(colOrderId)) : "";
                String finishedPartNumber = colPartNum >= 0 ? getCellValueAsString(row.getCell(colPartNum)) : "";
                if (orderId.isEmpty() || finishedPartNumber.isEmpty()) { skipCount++; continue; }

                // 🌟 联合防重：订单号 + 零件号 (剔除本表内重复和数据库历史重复)
                String uniqueKey = orderId + "_" + finishedPartNumber;
                if (seenKeys.contains(uniqueKey)) { skipCount++; continue; }
                if (orderRepo.existsByOrderIdAndFinishedPartNumber(orderId, finishedPartNumber)) { skipCount++; continue; }
                seenKeys.add(uniqueKey);

                ProductionOrder order = new ProductionOrder();
                order.setOrderId(orderId);
                order.setFinishedPartNumber(finishedPartNumber);
                if(colCustomer >= 0) order.setCustomerName(getCellValueAsString(row.getCell(colCustomer)));
                if(colSales >= 0) order.setSalesperson(getCellValueAsString(row.getCell(colSales)));
                if(colUnfinished >= 0) order.setUnfinishedMeters(parseBigDecimalSafely(getCellValueAsString(row.getCell(colUnfinished))));
                if(colProductName >= 0) order.setProductName(getCellValueAsString(row.getCell(colProductName)));
                if(colModelSpec >= 0) order.setModelSpec(getCellValueAsString(row.getCell(colModelSpec)));
                if(colMaterial >= 0) order.setMaterial(getCellValueAsString(row.getCell(colMaterial)));
                if(colColor >= 0) order.setColor(getCellValueAsString(row.getCell(colColor)));

                if(colMetersPerRoll >= 0) order.setMetersPerRoll(parseBigDecimalSafely(getCellValueAsString(row.getCell(colMetersPerRoll))));
                if(colRollCount >= 0) order.setRollCount(parseIntegerSafely(getCellValueAsString(row.getCell(colRollCount))));

                BigDecimal totalLen = colTotal >= 0 ? parseBigDecimalSafely(getCellValueAsString(row.getCell(colTotal))) : null;
                if(totalLen == null && order.getMetersPerRoll() != null && order.getRollCount() != null) {
                    totalLen = order.getMetersPerRoll().multiply(new BigDecimal(order.getRollCount()));
                }
                order.setTotalLength(totalLen);

                if(colOrderDate >= 0) order.setOrderDate(parseDateSafely(row.getCell(colOrderDate)));
                if(colDeliveryDate >= 0) order.setDeliveryDate(parseDateSafely(row.getCell(colDeliveryDate)));
                if(colRemarks >= 0) order.setRemarks(getCellValueAsString(row.getCell(colRemarks)));
                order.setEnteredBy(currentUser);

                orderRepo.save(order);
                successCount++;
            }
        }
        return "🛒 销售订单 Excel 解析完毕！导入 " + successCount + " 条明细，自动剔除重复或无效行 " + skipCount + " 条。";
    }

    /**
     * 导出销售订单为Excel
     *
     * @param year 导出年份（4位，按订单下达时间 orderDate 筛选）；null 表示导出全部（兼容旧行为）
     */
    public byte[] exportOrdersToExcel(Integer year) throws Exception {
        List<ProductionOrder> orders = year != null
                ? orderRepo.findByOrderDateBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                : orderRepo.findAllByOrderByCreatedAtDesc();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("销售订单明细");
            Row headerRow = sheet.createRow(0);

            String[] headers = {"客户名称", "订单号", "销售员", "未入库完成米数", "零件号", "品名", "规格型号", "材质", "胶色", "单卷长度", "卷数", "总数量(米)", "订单下达时间", "交货期", "备注"};

            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (ProductionOrder order : orders) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(order.getCustomerName() != null ? order.getCustomerName() : "");
                row.createCell(1).setCellValue(order.getOrderId() != null ? order.getOrderId() : "");
                row.createCell(2).setCellValue(order.getSalesperson() != null ? order.getSalesperson() : "");
                if(order.getUnfinishedMeters() != null) row.createCell(3).setCellValue(order.getUnfinishedMeters().doubleValue()); else row.createCell(3).setCellValue("");
                row.createCell(4).setCellValue(order.getFinishedPartNumber() != null ? order.getFinishedPartNumber() : "");
                row.createCell(5).setCellValue(order.getProductName() != null ? order.getProductName() : "");
                row.createCell(6).setCellValue(order.getModelSpec() != null ? order.getModelSpec() : "");
                row.createCell(7).setCellValue(order.getMaterial() != null ? order.getMaterial() : "");
                row.createCell(8).setCellValue(order.getColor() != null ? order.getColor() : "");

                if(order.getMetersPerRoll() != null) row.createCell(9).setCellValue(order.getMetersPerRoll().doubleValue());
                if(order.getRollCount() != null) row.createCell(10).setCellValue(order.getRollCount());
                if(order.getTotalLength() != null) row.createCell(11).setCellValue(order.getTotalLength().doubleValue());

                if (order.getOrderDate() != null) row.createCell(12).setCellValue(order.getOrderDate().toString());
                if (order.getDeliveryDate() != null) row.createCell(13).setCellValue(order.getDeliveryDate().toString());
                row.createCell(14).setCellValue(order.getRemarks() != null ? order.getRemarks() : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos); return bos.toByteArray();
        }
    }
}