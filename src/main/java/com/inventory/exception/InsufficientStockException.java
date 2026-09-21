package com.inventory.exception;

/**
 * Thrown when a requested quantity exceeds the stock currently available.
 * Carries the numbers so the UI layer can print a precise message.
 */
public class InsufficientStockException extends Exception {

    private final int productId;
    private final String productName;
    private final int requested;
    private final int available;

    public InsufficientStockException(int productId, String productName, int requested, int available) {
        super(String.format("Insufficient stock for '%s' (id=%d): requested %d, only %d available.",
                productName, productId, requested, available));
        this.productId = productId;
        this.productName = productName;
        this.requested = requested;
        this.available = available;
    }

    public int getProductId()      { return productId; }
    public String getProductName() { return productName; }
    public int getRequested()      { return requested; }
    public int getAvailable()      { return available; }
}
