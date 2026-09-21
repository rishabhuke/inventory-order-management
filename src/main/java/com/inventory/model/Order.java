package com.inventory.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An order placed by one customer, made up of one or more OrderItems.
 *
 * The total is COMPUTED from the items rather than stored, so there is a single
 * source of truth and it can never disagree with the line items.
 */
public class Order {

    /** Id used for an order that has not been saved to the database yet. */
    public static final int UNSAVED_ID = 0;

    private int orderId;
    private final int userId;
    private final LocalDateTime orderDate;
    private OrderStatus status;
    private final List<OrderItem> items = new ArrayList<>();

    /** Creates a brand-new order for the given customer (timestamped now, status PLACED). */
    public Order(int userId) {
        this(UNSAVED_ID, userId, LocalDateTime.now().withNano(0), OrderStatus.PLACED);
    }

    /** Creates an order with known values (e.g. loaded from the database). */
    public Order(int orderId, int userId, LocalDateTime orderDate, OrderStatus status) {
        if (orderDate == null || status == null) {
            throw new IllegalArgumentException("Order date and status must not be null");
        }
        this.orderId = orderId;
        this.userId = userId;
        this.orderDate = orderDate;
        this.status = status;
    }

    public void addItem(OrderItem item) {
        if (item == null) {
            throw new IllegalArgumentException("Order item must not be null");
        }
        items.add(item);
    }

    /** Sum of all line subtotals. */
    public BigDecimal getTotalAmount() {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItem item : items) {
            total = total.add(item.getSubtotal());
        }
        return total;
    }

    public int getOrderId()               { return orderId; }
    public int getUserId()                { return userId; }
    public LocalDateTime getOrderDate()   { return orderDate; }
    public OrderStatus getStatus()        { return status; }

    /** Read-only view: callers must go through addItem() to change the order. */
    public List<OrderItem> getItems()     { return Collections.unmodifiableList(items); }

    /** Called by the DAO once the database has generated the id. */
    public void setOrderId(int orderId)   { this.orderId = orderId; }

    public void setStatus(OrderStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Status must not be null");
        }
        this.status = status;
    }

    @Override
    public String toString() {
        return "Order{id=" + orderId + ", userId=" + userId + ", date=" + orderDate
                + ", status=" + status + ", items=" + items.size() + ", total=" + getTotalAmount() + "}";
    }
}
