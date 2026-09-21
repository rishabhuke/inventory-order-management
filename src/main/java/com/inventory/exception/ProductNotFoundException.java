package com.inventory.exception;

/** Thrown when a product id (or name) does not match any active product. */
public class ProductNotFoundException extends Exception {

    public ProductNotFoundException(int productId) {
        super("Product not found with id: " + productId);
    }

    public ProductNotFoundException(String message) {
        super(message);
    }
}
