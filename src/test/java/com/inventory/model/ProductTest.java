package com.inventory.model;

import com.inventory.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the stock-deduction rule, which lives in Product so it needs no database. */
class ProductTest {

    private static Product productWithStock(int stock) {
        return new Product(1, "Wireless Mouse", "Electronics", new BigDecimal("799.00"), stock);
    }

    @Test
    @DisplayName("deductStock reduces the stock by the requested quantity")
    void deductReducesStock() throws Exception {
        Product p = productWithStock(10);
        p.deductStock(3);
        assertEquals(7, p.getStockQuantity());
    }

    @Test
    @DisplayName("deductStock can take stock exactly down to zero")
    void deductExactlyAllStock() throws Exception {
        Product p = productWithStock(4);
        p.deductStock(4);
        assertEquals(0, p.getStockQuantity());
    }

    @Test
    @DisplayName("deductStock throws InsufficientStockException and leaves stock unchanged")
    void deductTooMuchThrowsAndKeepsStock() {
        Product p = productWithStock(5);

        InsufficientStockException e =
                assertThrows(InsufficientStockException.class, () -> p.deductStock(6));

        assertEquals(5, p.getStockQuantity());       // nothing was deducted
        assertEquals(6, e.getRequested());
        assertEquals(5, e.getAvailable());
        assertEquals(1, e.getProductId());
        assertTrue(e.getMessage().contains("Wireless Mouse"));
    }

    @Test
    @DisplayName("deductStock on empty stock throws")
    void deductFromEmptyStock() {
        assertThrows(InsufficientStockException.class, () -> productWithStock(0).deductStock(1));
    }

    @Test
    @DisplayName("deductStock rejects zero and negative quantities")
    void deductRejectsNonPositiveQuantity() {
        Product p = productWithStock(5);
        assertThrows(IllegalArgumentException.class, () -> p.deductStock(0));
        assertThrows(IllegalArgumentException.class, () -> p.deductStock(-2));
        assertEquals(5, p.getStockQuantity());
    }

    @Test
    @DisplayName("repeated deductions accumulate and stop at the limit")
    void repeatedDeductions() throws Exception {
        Product p = productWithStock(5);
        p.deductStock(2);
        p.deductStock(2);
        assertThrows(InsufficientStockException.class, () -> p.deductStock(2));
        assertEquals(1, p.getStockQuantity());
    }

    @Test
    @DisplayName("hasSufficientStock compares against current stock")
    void hasSufficientStock() {
        Product p = productWithStock(3);
        assertTrue(p.hasSufficientStock(3));
        assertFalse(p.hasSufficientStock(4));
    }

    @Test
    @DisplayName("constructor and setters reject invalid values")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> productWithStock(-1));
        assertThrows(IllegalArgumentException.class,
                () -> new Product(1, " ", "Cat", BigDecimal.ONE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Product(1, "Name", "Cat", new BigDecimal("-0.01"), 1));
    }
}
