package com.inventory.exception;

/**
 * Thrown when an order cannot be completed for a reason other than stock
 * (empty cart, database failure during the transaction, ...).
 * The original cause is preserved so it can be logged or inspected.
 */
public class OrderProcessingException extends Exception {

    public OrderProcessingException(String message) {
        super(message);
    }

    public OrderProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
