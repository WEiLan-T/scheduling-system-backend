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

    @Transactional
    public String saveOrders(List<ProductionOrder> orders, String currentUser) {
        if (orders == null || orders.isEmpty()) {
            return "订单明细不能为空";
        }

        // 为每一行明细打上操作人钢印
        for (ProductionOrder order : orders) {
            order.setEnteredBy(currentUser);
        }

        orderRepo.saveAll(orders);
        return "✅ 订单下达成功！订单号 [" + orders.get(0).getOrderId() + "]，共包含 " + orders.size() + " 行产品明细。";
    }
}