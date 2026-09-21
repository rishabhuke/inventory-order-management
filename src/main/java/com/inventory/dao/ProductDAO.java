package com.inventory.dao;

import com.inventory.exception.DatabaseException;
import com.inventory.model.Product;
import com.inventory.util.DBConnection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * All SQL for the products table. Every query uses a PreparedStatement, so user
 * input is always bound as a parameter and never concatenated into SQL (this is
 * what prevents SQL injection).
 *
 * Products are SOFT-deleted (is_active = 0): a product that appears in old orders
 * cannot be physically removed without breaking the foreign key from order_items.
 * Every read here therefore filters on is_active = 1.
 *
 * Two methods take a Connection parameter. They exist so that OrderDAO can run them
 * inside ITS transaction; the DAO that owns the transaction owns the Connection.
 */
public class ProductDAO {

    private static final String SELECT_ACTIVE =
            "SELECT product_id, name, category, price, stock_quantity FROM products WHERE is_active = 1";

    // ------------------------------------------------------------------ create

    /** Inserts the product and returns a copy that carries the database-generated id. */
    public Product insert(Product product) {
        String sql = "INSERT INTO products (name, category, price, stock_quantity) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, product.getName());
            ps.setString(2, product.getCategory());
            ps.setBigDecimal(3, product.getPrice());
            ps.setInt(4, product.getStockQuantity());
            ps.executeUpdate();
            int newId = DBConnection.lastInsertId(conn);
            return new Product(newId, product.getName(), product.getCategory(),
                    product.getPrice(), product.getStockQuantity());
        } catch (SQLException e) {
            throw new DatabaseException("Could not add product: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------- read

    public Optional<Product> findById(int productId) {
        try (Connection conn = DBConnection.getConnection()) {
            return findById(conn, productId);
        } catch (SQLException e) {
            throw new DatabaseException("Could not load product " + productId + ": " + e.getMessage(), e);
        }
    }

    /** Same lookup, but on a caller-supplied connection so it can join a transaction. */
    public Optional<Product> findById(Connection conn, int productId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE + " AND product_id = ?")) {
            ps.setInt(1, productId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapProduct(rs)) : Optional.empty();
            }
        }
    }

    /** All active products, ordered by id. Used to (re)build the in-memory cache. */
    public List<Product> findAllActive() {
        List<Product> products = new ArrayList<>();
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_ACTIVE + " ORDER BY product_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                products.add(mapProduct(rs));
            }
            return products;
        } catch (SQLException e) {
            throw new DatabaseException("Could not load products: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ update

    /** Overwrites name, category, price and stock. Returns false if no active product has that id. */
    public boolean update(Product product) {
        String sql = "UPDATE products SET name = ?, category = ?, price = ?, stock_quantity = ? "
                + "WHERE product_id = ? AND is_active = 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, product.getName());
            ps.setString(2, product.getCategory());
            ps.setBigDecimal(3, product.getPrice());
            ps.setInt(4, product.getStockQuantity());
            ps.setInt(5, product.getProductId());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Could not update product: " + e.getMessage(), e);
        }
    }

    /**
     * Atomic "check and decrement" in ONE statement.
     *
     * The WHERE clause (stock_quantity >= ?) is evaluated by the database at the
     * moment of the write, so stock can never go negative even if our in-memory
     * cache is stale. The CHECK (stock_quantity >= 0) in schema.sql is a second
     * safety net.
     *
     * @return true if stock was deducted, false if the product is missing/inactive
     *         or has less stock than requested (the caller works out which)
     */
    public boolean deductStockIfAvailable(Connection conn, int productId, int quantity) throws SQLException {
        String sql = "UPDATE products SET stock_quantity = stock_quantity - ? "
                + "WHERE product_id = ? AND is_active = 1 AND stock_quantity >= ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, quantity);
            ps.setInt(2, productId);
            ps.setInt(3, quantity);
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ delete

    /** Soft delete: hides the product but keeps order history intact. Returns false if not found. */
    public boolean softDelete(int productId) {
        String sql = "UPDATE products SET is_active = 0 WHERE product_id = ? AND is_active = 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Could not delete product: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- mapping

    private Product mapProduct(ResultSet rs) throws SQLException {
        // SQLite has no true DECIMAL type: a value like 799.00 comes back as 799.
        // Normalise to 2 decimal places so display and comparisons are consistent.
        BigDecimal price = rs.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP);
        return new Product(rs.getInt("product_id"), rs.getString("name"), rs.getString("category"),
                price, rs.getInt("stock_quantity"));
    }
}
