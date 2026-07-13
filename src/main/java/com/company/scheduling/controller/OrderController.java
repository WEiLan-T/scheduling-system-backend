package com.company.scheduling.controller;

import com.company.scheduling.domain.ProductionOrder;
import com.company.scheduling.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workshops/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

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
}