package com.inventory.model;

import java.math.BigDecimal;

/**
 * One line of an order. unitPrice is a SNAPSHOT of the product price at the
 * moment of purchase, so later price changes never rewrite order history.
 * productName is carried along (filled by a JOIN) so history can be displayed
 * without a second lookup, even if the product has since been soft-deleted.
 */
public class OrderItem {

    private final int productId;
    private final String productName;
    private final int quantity;
    private final BigDecimal unitPrice;

    public OrderItem(int productId, String productName, int quantity, BigDecimal unitPrice) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Order item quantity must be positive");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("Unit price must not be negative");
        }
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public int getProductId()        { return productId; }
    public String getProductName()   { return productName; }
    public int getQuantity()         { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }

    /** unitPrice x quantity */
    public BigDecimal getSubtotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    @Override
    public String toString() {
        return productName + " x" + quantity + " @ " + unitPrice + " = " + getSubtotal();
    }
}
