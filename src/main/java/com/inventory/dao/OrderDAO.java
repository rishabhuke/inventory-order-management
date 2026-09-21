package com.inventory.dao;

import com.inventory.exception.DatabaseException;
import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.OrderProcessingException;
import com.inventory.exception.ProductNotFoundException;
import com.inventory.model.Order;
import com.inventory.model.OrderItem;
import com.inventory.model.OrderStatus;
import com.inventory.model.Product;
import com.inventory.util.DBConnection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All SQL for the orders and order_items tables, including the order-placement
 * TRANSACTION.
 *
 * Why the transaction lives in the DAO: "no SQL/JDBC code in the service layer"
 * means the code that opens a Connection, turns off auto-commit and commits or
 * rolls back has to live here. The service decides WHAT should be ordered; this
 * class guarantees it is saved all-or-nothing.
 */
public class OrderDAO {

    /** Always writes seconds, so the text form of dates is uniform. */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * One JOIN loads orders together with their items and product names
     * (a single query instead of one query per order, avoiding the "N+1" problem).
     * Rows are ordered by order_id ascending, i.e. oldest first.
     */
    private static final String ORDERS_WITH_ITEMS =
            "SELECT o.order_id, o.user_id, o.order_date, o.status, "
          + "       oi.product_id, p.name AS product_name, oi.quantity, oi.unit_price "
          + "FROM orders o "
          + "JOIN order_items oi ON oi.order_id = o.order_id "
          + "JOIN products p     ON p.product_id = oi.product_id ";

    private static final String ORDER_BY_OLDEST_FIRST = " ORDER BY o.order_id ASC, oi.order_item_id ASC";

    private final ProductDAO productDAO;

    public OrderDAO(ProductDAO productDAO) {
        this.productDAO = productDAO;
    }

    // ------------------------------------------------------- place (transaction)

    /**
     * Saves the order, its items and the stock deductions ATOMICALLY.
     *
     * All statements run on one connection with auto-commit off:
     *   1. insert the orders row
     *   2. for each item: deduct stock (guarded UPDATE), then insert the order_items row
     *   3. commit
     * If ANY step fails (not enough stock, unknown product, SQL error) the whole
     * transaction is rolled back, so we can never end up with stock deducted but no
     * order, or an order without its stock deducted.
     *
     * @param order a new order (id not yet assigned); on success it receives its generated id
     * @throws InsufficientStockException if some product has less stock than requested
     * @throws ProductNotFoundException   if some product does not exist or was deleted
     * @throws OrderProcessingException   if the order is empty or the database fails
     */
    public Order placeOrder(Order order)
            throws InsufficientStockException, ProductNotFoundException, OrderProcessingException {
        if (order.getItems().isEmpty()) {
            throw new OrderProcessingException("Cannot place an empty order.");
        }

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);   // begin transaction
            try {
                int orderId = insertOrderRow(conn, order);

                for (OrderItem item : order.getItems()) {
                    int productId = item.getProductId();

                    if (!productDAO.deductStockIfAvailable(conn, productId, item.getQuantity())) {
                        // Nothing was deducted, so work out WHY to report it precisely.
                        Product current = productDAO.findById(conn, productId)
                                .orElseThrow(() -> new ProductNotFoundException(productId));
                        throw new InsufficientStockException(current.getProductId(), current.getName(),
                                item.getQuantity(), current.getStockQuantity());
                    }
                    insertItemRow(conn, orderId, item);
                }

                conn.commit();           // everything succeeded: make it permanent
                order.setOrderId(orderId);
                return order;

            } catch (SQLException | RuntimeException | InsufficientStockException | ProductNotFoundException e) {
                rollbackQuietly(conn, e); // anything failed: undo every change made above
                throw e;
            }
        } catch (SQLException e) {
            throw new OrderProcessingException("Order could not be saved: " + e.getMessage(), e);
        }
    }

    private int insertOrderRow(Connection conn, Order order) throws SQLException {
        String sql = "INSERT INTO orders (user_id, order_date, status) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, order.getUserId());
            ps.setString(2, order.getOrderDate().format(DATE_FORMAT));
            ps.setString(3, order.getStatus().name());
            ps.executeUpdate();
        }
        return DBConnection.lastInsertId(conn);
    }

    private void insertItemRow(Connection conn, int orderId, OrderItem item) throws SQLException {
        String sql = "INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            ps.setInt(2, item.getProductId());
            ps.setInt(3, item.getQuantity());
            ps.setBigDecimal(4, item.getUnitPrice());
            ps.executeUpdate();
        }
    }

    private void rollbackQuietly(Connection conn, Exception original) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    // -------------------------------------------------------------------- read

    /** One customer's orders, OLDEST FIRST (the service pushes them onto a stack to show newest first). */
    public List<Order> findByUser(int userId) {
        return query(ORDERS_WITH_ITEMS + "WHERE o.user_id = ?" + ORDER_BY_OLDEST_FIRST,
                ps -> ps.setInt(1, userId));
    }

    /** All orders with the given status, OLDEST FIRST (e.g. PLACED orders waiting to be processed). */
    public List<Order> findByStatus(OrderStatus status) {
        return query(ORDERS_WITH_ITEMS + "WHERE o.status = ?" + ORDER_BY_OLDEST_FIRST,
                ps -> ps.setString(1, status.name()));
    }

    // ------------------------------------------------------------------ update

    /** PLACED -> PROCESSED. Returns false if the order does not exist or was already processed. */
    public boolean markProcessed(int orderId) {
        String sql = "UPDATE orders SET status = 'PROCESSED' WHERE order_id = ? AND status = 'PLACED'";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, orderId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Could not update order status: " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- mapping

    /** Lets each caller bind its own parameters without duplicating the try/catch boilerplate. */
    @FunctionalInterface
    private interface ParameterBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Order> query(String sql, ParameterBinder binder) {
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return mapOrders(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Could not load orders: " + e.getMessage(), e);
        }
    }

    /**
     * Turns the flat JOIN rows (one row per order item) back into Order objects that
     * each own a list of items. LinkedHashMap keeps the orders in the order they arrived.
     */
    private List<Order> mapOrders(ResultSet rs) throws SQLException {
        Map<Integer, Order> ordersById = new LinkedHashMap<>();
        while (rs.next()) {
            int orderId = rs.getInt("order_id");
            Order order = ordersById.get(orderId);
            if (order == null) {
                order = new Order(orderId,
                        rs.getInt("user_id"),
                        LocalDateTime.parse(rs.getString("order_date"), DATE_FORMAT),
                        OrderStatus.valueOf(rs.getString("status")));
                ordersById.put(orderId, order);
            }
            BigDecimal unitPrice = rs.getBigDecimal("unit_price").setScale(2, RoundingMode.HALF_UP);
            order.addItem(new OrderItem(rs.getInt("product_id"), rs.getString("product_name"),
                    rs.getInt("quantity"), unitPrice));
        }
        return new ArrayList<>(ordersById.values());
    }
}
