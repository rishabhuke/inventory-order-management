package com.inventory.model;

import com.inventory.exception.InsufficientStockException;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A sellable product.
 *
 * Price is a BigDecimal (never double) because binary floating point cannot
 * represent values like 0.10 exactly, which is unacceptable for money.
 *
 * The stock-deduction rule lives HERE, inside the entity, so the invariant
 * "stock never goes negative" is enforced in one place and can be unit-tested
 * without a database.
 */
public class Product {

    /** Id used for a product that has not been saved to the database yet. */
    public static final int UNSAVED_ID = 0;

    private final int productId;
    private String name;
    private String category;
    private BigDecimal price;
    private int stockQuantity;

    /** Creates a product that is not yet persisted (id = UNSAVED_ID). */
    public Product(String name, String category, BigDecimal price, int stockQuantity) {
        this(UNSAVED_ID, name, category, price, stockQuantity);
    }

    /** Creates a product with a known id (e.g. loaded from the database). */
    public Product(int productId, String name, String category, BigDecimal price, int stockQuantity) {
        this.productId = productId;
        setName(name);
        setCategory(category);
        setPrice(price);
        setStockQuantity(stockQuantity);
    }

    // ---------------------------- business logic ----------------------------

    public boolean hasSufficientStock(int quantity) {
        return quantity <= stockQuantity;
    }

    /**
     * Reduces stock by the given quantity.
     *
     * @throws InsufficientStockException if quantity exceeds the current stock
     *                                    (stock is left unchanged in that case)
     * @throws IllegalArgumentException   if quantity is not positive (a programming error)
     */
    public void deductStock(int quantity) throws InsufficientStockException {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity to deduct must be positive, got " + quantity);
        }
        if (!hasSufficientStock(quantity)) {
            throw new InsufficientStockException(productId, name, quantity, stockQuantity);
        }
        stockQuantity -= quantity;
    }

    // ------------------------------ accessors -------------------------------

    public int getProductId()       { return productId; }
    public String getName()         { return name; }
    public String getCategory()     { return category; }
    public BigDecimal getPrice()    { return price; }
    public int getStockQuantity()   { return stockQuantity; }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name must not be blank");
        }
        this.name = name.trim();
    }

    public void setCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Product category must not be blank");
        }
        this.category = category.trim();
    }

    public void setPrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Price must not be negative");
        }
        this.price = price;
    }

    public void setStockQuantity(int stockQuantity) {
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("Stock quantity must not be negative");
        }
        this.stockQuantity = stockQuantity;
    }

    // -------------------------- identity & display ---------------------------

    /** Two products are the same product if they have the same (saved) id. */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product other)) return false;
        return productId != UNSAVED_ID && productId == other.productId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(productId);
    }

    @Override
    public String toString() {
        return "Product{id=" + productId + ", name='" + name + "', category='" + category
                + "', price=" + price + ", stock=" + stockQuantity + "}";
    }
}
