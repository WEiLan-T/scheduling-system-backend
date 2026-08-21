package com.company.scheduling.controller;

import com.company.scheduling.domain.ProductionOrder;
import com.company.scheduling.dto.PageResponse;
import com.company.scheduling.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workshops/orders")
public class OrderController {

    private final OrderService orderService;
    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<?> getAllOrders(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) String finishedPartNumber) {
        // 未传 page/size 时保持旧全量行为（返回数组）
        if (page == null && size == null) {
            return ResponseEntity.ok(orderService.getAllOrders());
        }
        int p = page == null ? 0 : Math.max(page, 0);
        int s = size == null ? 20 : Math.max(size, 1);
        return ResponseEntity.ok(PageResponse.from(orderService.searchOrders(p, s, keyword, orderId, finishedPartNumber)));
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> submitOrders(@RequestBody List<ProductionOrder> orders, Principal principal) {
        return ResponseEntity.ok(orderService.saveOrders(orders, principal.getName()));
    }

    @PutMapping("/{orderId}")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> updateOrder(@PathVariable String orderId, @RequestBody List<ProductionOrder> orders, Principal principal) {
        return ResponseEntity.ok(orderService.updateOrder(orderId, orders, principal.getName()));
    }

    @DeleteMapping("/{orderId}")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> deleteOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.deleteOrder(orderId));
    }

    // 🌟 新增：Excel 导入接口
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> importOrders(@RequestParam("file") MultipartFile file, Principal principal) {
        try {
            return ResponseEntity.ok(orderService.importOrderExcel(file, principal.getName()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Excel导入失败: " + e.getMessage());
        }
    }

    // 🌟 新增：Excel 导出接口（支持按年份筛选，避免数据量大时整体导出报错）
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> exportOrders(
            @RequestParam(value = "year", required = false) Integer year) {
        try {
            // year 为 null 时导出全部（兼容旧行为）；传年份则只导出该年下达的订单
            byte[] bytes = orderService.exportOrdersToExcel(year);
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            String filename = year != null ? "Production_Orders_Dashboard_" + year + ".xlsx" : "Production_Orders_Dashboard.xlsx";
            headers.setContentDispositionFormData("attachment", filename);
            return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }
}