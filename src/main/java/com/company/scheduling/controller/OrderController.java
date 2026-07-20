package com.company.scheduling.controller;

import com.company.scheduling.domain.ProductionOrder;
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
    public ResponseEntity<List<ProductionOrder>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
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

    // 🌟 新增：Excel 导出接口
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> exportOrders() {
        try {
            byte[] bytes = orderService.exportOrdersToExcel();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", "Production_Orders_Dashboard.xlsx");
            return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }
}