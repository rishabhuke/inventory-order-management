package com.inventory.service;

import com.inventory.dao.OrderDAO;
import com.inventory.dao.ProductDAO;
import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.InvalidUserException;
import com.inventory.exception.OrderProcessingException;
import com.inventory.exception.ProductNotFoundException;
import com.inventory.model.Order;
import com.inventory.model.OrderStatus;
import com.inventory.model.Product;
import com.inventory.model.Role;
import com.inventory.model.User;
import com.inventory.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the order flow against a real (throw-away) SQLite database.
 * User ids match the sample data in schema.sql: admin = 1, alice = 2, bob = 3.
 * Sample products used: 1 Wireless Mouse (799.00, stock 50), 2 Mechanical Keyboard (3499.00, stock 25),
 * 4 HD Webcam (2199.00, stock 2), 6 Phone Stand (299.00, stock 40), 8 Notebook A5 (89.00, stock 200).
 */
class OrderServiceTest {

    private static final User ADMIN = new User(1, "admin", Role.ADMIN);
    private static final User ALICE = new User(2, "alice", Role.CUSTOMER);
    private static final User BOB = new User(3, "bob", Role.CUSTOMER);

    private ProductService productService;
    private OrderService orderService;

    @BeforeEach
    void freshDatabase() {
        TestDatabase.reset();
        productService = new ProductService(new ProductDAO());
        orderService = newOrderService(productService);
    }

    private static OrderService newOrderService(ProductService products) {
        return new OrderService(new OrderDAO(new ProductDAO()), products);
    }

    /** cart(1, 2, 6, 1) = product 1 x2 and product 6 x1, in that order. */
    private static Map<Integer, Integer> cart(int... productIdQuantityPairs) {
        Map<Integer, Integer> cart = new LinkedHashMap<>();
        for (int i = 0; i < productIdQuantityPairs.length; i += 2) {
            cart.put(productIdQuantityPairs[i], productIdQuantityPairs[i + 1]);
        }
        return cart;
    }

    private int stockInDatabase(int productId) {
        return new ProductDAO().findById(productId).orElseThrow().getStockQuantity();
    }

    private static List<Integer> orderIds(List<Order> orders) {
        return orders.stream().map(Order::getOrderId).toList();
    }

    private static List<Integer> drain(Deque<Order> stack) {
        List<Integer> ids = new ArrayList<>();
        while (!stack.isEmpty()) {
            ids.add(stack.pop().getOrderId());
        }
        return ids;
    }

    // ------------------------------------------------------------ placing orders

    @Test
    @DisplayName("placing an order saves it, deducts stock in the DB and the cache, and queues it")
    void placeOrderSuccess() throws Exception {
        Order order = orderService.placeOrder(ALICE, cart(1, 2, 6, 1));

        assertTrue(order.getOrderId() > 0);
        assertEquals(OrderStatus.PLACED, order.getStatus());
        assertEquals(0, new BigDecimal("1897.00").compareTo(order.getTotalAmount()));   // 2 x 799 + 299
        assertEquals(48, stockInDatabase(1));
        assertEquals(39, stockInDatabase(6));
        assertEquals(48, productService.getProduct(1).getStockQuantity());               // cache in sync
        assertEquals(1, orderService.getPendingCount());
    }

    @Test
    @DisplayName("not enough stock: InsufficientStockException, nothing changes")
    void insufficientStockChangesNothing() {
        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> orderService.placeOrder(ALICE, cart(4, 3)));

        assertEquals(3, e.getRequested());
        assertEquals(2, e.getAvailable());
        assertEquals(2, stockInDatabase(4));
        assertEquals(0, orderService.getPendingCount());
    }

    @Test
    @DisplayName("ATOMICITY: a stale cache lets the order reach the database, which rolls EVERYTHING back")
    void staleCacheIsCaughtByTheDatabaseAndRolledBack() throws Exception {
        // Someone empties the webcam stock directly in the database, behind the cache's back.
        new ProductDAO().update(new Product(4, "HD Webcam", "Electronics", new BigDecimal("2199.00"), 0));
        assertEquals(2, productService.getProduct(4).getStockQuantity());   // cache is now stale

        // Line 1 (mouse) is fine; line 2 (webcam) fails inside the transaction, after line 1's stock was deducted.
        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> orderService.placeOrder(ALICE, cart(1, 2, 4, 1)));

        assertEquals(0, e.getAvailable());
        assertEquals(50, stockInDatabase(1));                               // mouse deduction was rolled back
        assertEquals(0, orderService.getOrderHistory(ALICE).size());        // no order row was left behind
        assertEquals(0, orderService.getPendingCount());
        assertEquals(0, productService.getProduct(4).getStockQuantity());   // cache healed itself
        assertEquals(50, productService.getProduct(1).getStockQuantity());
    }

    @Test
    @DisplayName("stock can be sold down to exactly zero, then further orders are refused")
    void sellOutThenRefuse() throws Exception {
        orderService.placeOrder(ALICE, cart(4, 2));
        assertEquals(0, stockInDatabase(4));

        assertThrows(InsufficientStockException.class, () -> orderService.placeOrder(BOB, cart(4, 1)));
        assertEquals(0, stockInDatabase(4));
    }

    @Test
    @DisplayName("bad carts are rejected: empty, zero quantity, unknown product, deleted product")
    void badCarts() throws Exception {
        assertThrows(OrderProcessingException.class, () -> orderService.placeOrder(ALICE, cart()));
        assertThrows(OrderProcessingException.class, () -> orderService.placeOrder(ALICE, null));
        assertThrows(OrderProcessingException.class, () -> orderService.placeOrder(ALICE, cart(1, 0)));
        assertThrows(OrderProcessingException.class, () -> orderService.placeOrder(ALICE, cart(1, -3)));
        assertThrows(ProductNotFoundException.class, () -> orderService.placeOrder(ALICE, cart(999, 1)));

        productService.deleteProduct(ADMIN, 6);
        assertThrows(ProductNotFoundException.class, () -> orderService.placeOrder(ALICE, cart(6, 1)));
        assertEquals(50, stockInDatabase(1));
        assertEquals(0, orderService.getPendingCount());
    }

    @Test
    @DisplayName("order history keeps the price paid even after the product price changes")
    void priceSnapshotSurvivesPriceChange() throws Exception {
        orderService.placeOrder(ALICE, cart(1, 1));
        productService.updateProduct(ADMIN, 1, "Wireless Mouse", "Electronics", new BigDecimal("999.00"), 49);

        Order fromHistory = orderService.getOrderHistory(ALICE).peek();
        assertEquals(0, new BigDecimal("799.00").compareTo(fromHistory.getItems().get(0).getUnitPrice()));
        assertEquals("Wireless Mouse", fromHistory.getItems().get(0).getProductName());
    }

    // ---------------------------------------------------------------- roles

    @Test
    @DisplayName("role rules: only customers order and see history, only admins process")
    void roleRules() {
        assertThrows(InvalidUserException.class, () -> orderService.placeOrder(ADMIN, cart(1, 1)));
        assertThrows(InvalidUserException.class, () -> orderService.placeOrder(null, cart(1, 1)));
        assertThrows(InvalidUserException.class, () -> orderService.getOrderHistory(ADMIN));
        assertThrows(InvalidUserException.class, () -> orderService.processNextOrder(ALICE));
        assertThrows(InvalidUserException.class, () -> orderService.getPendingOrders(ALICE));
        assertEquals(50, stockInDatabase(1));
    }

    // ------------------------------------------------------ priority queue

    @Test
    @DisplayName("PriorityQueue: highest order value is processed first, ties oldest first")
    void queueProcessesByValueThenAge() throws Exception {
        orderService.placeOrder(ALICE, cart(8, 1));   // order 1:   89.00
        orderService.placeOrder(ALICE, cart(2, 1));   // order 2: 3499.00
        orderService.placeOrder(BOB,   cart(1, 1));   // order 3:  799.00
        orderService.placeOrder(BOB,   cart(1, 1));   // order 4:  799.00 (ties with order 3)

        assertEquals(List.of(2, 3, 4, 1), orderIds(orderService.getPendingOrders(ADMIN)));
        assertEquals(4, orderService.getPendingCount());                    // viewing removes nothing

        List<Integer> processedInOrder = new ArrayList<>();
        Optional<Order> next;
        while ((next = orderService.processNextOrder(ADMIN)).isPresent()) {
            assertEquals(OrderStatus.PROCESSED, next.get().getStatus());
            processedInOrder.add(next.get().getOrderId());
        }
        assertEquals(List.of(2, 3, 4, 1), processedInOrder);
        assertEquals(0, orderService.getPendingCount());
        assertEquals(Optional.empty(), orderService.processNextOrder(ADMIN));
    }

    @Test
    @DisplayName("processing is saved in the database, and the queue is rebuilt from it after a restart")
    void queueSurvivesRestart() throws Exception {
        orderService.placeOrder(ALICE, cart(8, 1));   // order 1:   89.00
        orderService.placeOrder(ALICE, cart(2, 1));   // order 2: 3499.00
        orderService.placeOrder(BOB,   cart(1, 1));   // order 3:  799.00
        orderService.processNextOrder(ADMIN);          // processes order 2

        OrderService restarted = newOrderService(new ProductService(new ProductDAO()));

        assertEquals(2, restarted.getPendingCount());
        assertEquals(List.of(3, 1), orderIds(restarted.getPendingOrders(ADMIN)));
    }

    // ---------------------------------------------------------- history stack

    @Test
    @DisplayName("Stack: order history pops newest first, and only shows the customer's own orders")
    void historyIsNewestFirstAndPrivate() throws Exception {
        orderService.placeOrder(ALICE, cart(1, 1));   // order 1
        orderService.placeOrder(BOB,   cart(6, 2));   // order 2
        orderService.placeOrder(ALICE, cart(8, 5));   // order 3
        orderService.placeOrder(ALICE, cart(2, 1, 6, 1));   // order 4, two lines

        Deque<Order> aliceHistory = orderService.getOrderHistory(ALICE);
        assertEquals(4, aliceHistory.peek().getOrderId());                   // top of the stack = newest
        assertEquals(2, aliceHistory.peek().getItems().size());
        assertEquals(List.of(4, 3, 1), drain(aliceHistory));

        assertEquals(List.of(2), drain(orderService.getOrderHistory(BOB)));
        assertTrue(orderService.getOrderHistory(ALICE).stream().noneMatch(o -> o.getUserId() != ALICE.getUserId()));
        assertFalse(orderService.getOrderHistory(BOB).isEmpty());
    }
}
