package com.inventory.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {

    @Test
    @DisplayName("order total is the exact sum of quantity x unit price over all items")
    void totalIsExactSum() {
        Order order = new Order(1);
        order.addItem(new OrderItem(1, "Mouse", 2, new BigDecimal("799.00")));
        order.addItem(new OrderItem(2, "Pen", 3, new BigDecimal("0.10")));

        // 2 x 799.00 + 3 x 0.10 = 1598.30. With double this kind of sum drifts; BigDecimal does not.
        assertEquals(0, new BigDecimal("1598.30").compareTo(order.getTotalAmount()));
    }

    @Test
    @DisplayName("an order with no items has a zero total")
    void emptyOrderTotalIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(new Order(1).getTotalAmount()));
    }

    @Test
    @DisplayName("the item list is read-only from outside")
    void itemsAreReadOnly() {
        Order order = new Order(1);
        assertThrows(UnsupportedOperationException.class,
                () -> order.getItems().add(new OrderItem(1, "Mouse", 1, BigDecimal.ONE)));
    }

    @Test
    @DisplayName("order items reject non-positive quantities")
    void itemRejectsBadQuantity() {
        assertThrows(IllegalArgumentException.class, () -> new OrderItem(1, "Mouse", 0, BigDecimal.ONE));
    }
}
