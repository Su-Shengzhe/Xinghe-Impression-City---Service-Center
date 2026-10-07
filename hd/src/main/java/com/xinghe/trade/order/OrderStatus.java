package com.xinghe.trade.order;

import java.util.List;

/** Derives a parent order status from the current statuses of its store orders. */
final class OrderStatus {
    private OrderStatus() {}

    static String fromChildren(List<TradeOrder> children) {
        if (children.isEmpty()) return null;
        if (children.stream().anyMatch(child -> "PENDING_PAYMENT".equals(child.getStatus()))) {
            return "PENDING_PAYMENT";
        }
        if (children.stream().allMatch(child -> "CANCELLED".equals(child.getStatus()))) return "CANCELLED";
        if (children.stream().allMatch(child -> "SHIPPED".equals(child.getStatus()))) return "SHIPPED";
        if (children.stream().allMatch(child -> "CANCELLED".equals(child.getStatus()) ||
                "SHIPPED".equals(child.getStatus()))) return "COMPLETED";
        return "PAID";
    }
}