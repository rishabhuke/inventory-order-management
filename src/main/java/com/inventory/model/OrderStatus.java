package com.inventory.model;

/**
 * PLACED    - order saved and stock already deducted, waiting in the processing queue.
 * PROCESSED - admin has taken it off the queue and fulfilled it.
 */
public enum OrderStatus {
    PLACED,
    PROCESSED
}
