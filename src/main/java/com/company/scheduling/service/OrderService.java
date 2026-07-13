package com.company.scheduling.service;

import com.company.scheduling.domain.ProductionOrder;
import com.company.scheduling.repository.ProductionOrderRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {
    private final ProductionOrderRepo orderRepo;

    public OrderService(ProductionOrderRepo orderRepo) {
        this.orderRepo = orderRepo;
    }

    public List<ProductionOrder> getAllOrders() {
        return orderRepo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public String saveOrders(List<ProductionOrder> orders, String currentUser) {
        if (orders == null || orders.isEmpty()) return "订单明细不能为空";
        for (ProductionOrder order : orders) { order.setEnteredBy(currentUser); }
        orderRepo.saveAll(orders);
        return "✅ 订单下达成功！订单号 [" + orders.get(0).getOrderId() + "]，共包含 " + orders.size() + " 行明细。";
    }

    // 🌟 新增：修改订单 (先删旧的，再存新的)
    @Transactional
    public String updateOrder(String orderId, List<ProductionOrder> orders, String currentUser) {
        orderRepo.deleteByOrderId(orderId);
        for (ProductionOrder order : orders) { order.setEnteredBy(currentUser); }
        orderRepo.saveAll(orders);
        return "✅ 订单 [" + orderId + "] 修正更新成功！";
    }

    // 🌟 新增：删除订单
    @Transactional
    public String deleteOrder(String orderId) {
        orderRepo.deleteByOrderId(orderId);
        return "🗑️ 订单 [" + orderId + "] 及其所有明细已从物理磁盘销毁！";
    }
}