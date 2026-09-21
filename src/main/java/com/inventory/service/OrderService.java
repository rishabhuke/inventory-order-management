package com.inventory.service;

import com.inventory.dao.OrderDAO;
import com.inventory.exception.DatabaseException;
import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.InvalidUserException;
import com.inventory.exception.OrderProcessingException;
import com.inventory.exception.ProductNotFoundException;
import com.inventory.model.Order;
import com.inventory.model.OrderItem;
import com.inventory.model.OrderStatus;
import com.inventory.model.Product;
import com.inventory.model.User;
import com.inventory.util.SortUtil;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Order business logic: placing orders, the processing queue and order history.
 *
 * LIFE OF AN ORDER
 *   1. placeOrder(): quick validation against the cache, then OrderDAO saves the order AND deducts
 *      the stock in one database transaction. The order is now PLACED and joins the queue.
 *   2. processNextOrder(): an admin takes the highest-priority order off the queue and marks it
 *      PROCESSED (think "packed and shipped").
 *
 * The queue is an in-memory PriorityQueue, but the DATABASE is the source of truth: at start-up
 * the queue is rebuilt from every order still in PLACED status, so no pending order is lost when
 * the program restarts.
 */
public class OrderService {

    /**
     * Processing priority: HIGHEST ORDER VALUE FIRST; equal values are served oldest first (FIFO,
     * because ids only grow). Known trade-off: strict value priority can starve small orders
     * while big ones keep arriving. A real system would add "aging" (raise an order's priority
     * the longer it waits); that is listed under future improvements in the README.
     */
    private static final Comparator<Order> PROCESSING_PRIORITY =
            Comparator.comparing(Order::getTotalAmount, Comparator.<BigDecimal>reverseOrder())
                    .thenComparingInt(Order::getOrderId);

    private final OrderDAO orderDAO;
    private final ProductService productService;
    private final PriorityQueue<Order> pendingQueue = new PriorityQueue<>(PROCESSING_PRIORITY);

    public OrderService(OrderDAO orderDAO, ProductService productService) {
        this.orderDAO = orderDAO;
        this.productService = productService;
        for (Order pending : orderDAO.findByStatus(OrderStatus.PLACED)) {
            pendingQueue.offer(pending);
        }
    }

    // ------------------------------------------------------------ place order

    /**
     * Places an order for a customer.
     *
     * @param cart product id -> quantity (a Map, so a product can appear only once)
     * @throws ProductNotFoundException   unknown or deleted product in the cart
     * @throws InsufficientStockException not enough stock for some product (nothing is changed)
     * @throws OrderProcessingException   empty cart, bad quantity or a database failure
     * @throws InvalidUserException       the user is not a customer
     */
    public Order placeOrder(User customer, Map<Integer, Integer> cart)
            throws InvalidUserException, ProductNotFoundException,
                   InsufficientStockException, OrderProcessingException {
        UserService.requireCustomer(customer);
        if (cart == null || cart.isEmpty()) {
            throw new OrderProcessingException("Your cart is empty.");
        }

        // Step 1 - fast validation against the cache, so most mistakes get a clear message
        // without opening a database transaction. The price of each item is captured HERE
        // (a snapshot for the order history).
        Order order = new Order(customer.getUserId());
        for (Map.Entry<Integer, Integer> line : cart.entrySet()) {
            int productId = line.getKey();
            int quantity = line.getValue();
            if (quantity <= 0) {
                throw new OrderProcessingException("Quantity must be at least 1 (product id " + productId + ").");
            }
            Product product = productService.getProduct(productId);
            if (!product.hasSufficientStock(quantity)) {
                throw new InsufficientStockException(productId, product.getName(), quantity,
                        product.getStockQuantity());
            }
            order.addItem(new OrderItem(productId, product.getName(), quantity, product.getPrice()));
        }

        // Step 2 - the authoritative, ATOMIC step. The DAO re-checks stock inside the database,
        // so even a stale cache cannot cause overselling.
        try {
            orderDAO.placeOrder(order);
        } finally {
            refreshCachedProducts(order);
        }

        pendingQueue.offer(order);
        return order;
    }

    /**
     * After an order attempt (successful OR rolled back) re-read the affected products from the
     * database. We never subtract from the cache by hand: copying the database's answer cannot
     * drift, and it also repairs a stale cache when the database refused the order.
     *
     * Best effort on purpose: the outcome of the order is already decided, and if this refresh
     * itself fails the worst case is a briefly stale cache (stock is still protected by the DAO).
     */
    private void refreshCachedProducts(Order order) {
        for (OrderItem item : order.getItems()) {
            try {
                productService.refreshProduct(item.getProductId());
            } catch (DatabaseException ignored) {
                // see method comment
            }
        }
    }

    // ------------------------------------------------------ processing queue

    /**
     * Takes the highest-priority pending order off the queue and marks it PROCESSED.
     * poll() is O(log n). Returns empty if nothing is waiting.
     */
    public Optional<Order> processNextOrder(User admin) throws InvalidUserException {
        UserService.requireAdmin(admin);

        Order next;
        while ((next = pendingQueue.poll()) != null) {
            boolean updated;
            try {
                updated = orderDAO.markProcessed(next.getOrderId());
            } catch (DatabaseException e) {
                pendingQueue.offer(next);          // the update failed: put the order back, do not lose it
                throw e;
            }
            if (updated) {
                next.setStatus(OrderStatus.PROCESSED);
                return Optional.of(next);
            }
            // updated == false: the database says this order was already processed, so the queue
            // entry was stale. Drop it and try the next one.
        }
        return Optional.empty();
    }

    /**
     * The waiting orders in the order they WILL be processed, without removing anything.
     * (A PriorityQueue only guarantees its head, so iterating it directly is not in priority
     * order; we copy it and sort with our own merge sort using the same comparator.)
     */
    public List<Order> getPendingOrders(User admin) throws InvalidUserException {
        UserService.requireAdmin(admin);
        return SortUtil.mergeSort(new ArrayList<>(pendingQueue), PROCESSING_PRIORITY);
    }

    public int getPendingCount() {
        return pendingQueue.size();
    }

    // ---------------------------------------------------------- order history

    /**
     * A customer's orders as a STACK with the MOST RECENT ORDER ON TOP (LIFO).
     *
     * The DAO returns orders oldest-first; pushing them one by one leaves the newest order on
     * top, so the caller just pop()s until the stack is empty to print newest-first.
     * (An ArrayDeque used through push/pop is the modern Java stack; the old java.util.Stack
     * class is synchronised legacy and discouraged. The same order could be had from
     * "ORDER BY order_id DESC" in SQL; the stack is here to model LIFO access explicitly.)
     */
    public Deque<Order> getOrderHistory(User customer) throws InvalidUserException {
        UserService.requireCustomer(customer);
        Deque<Order> stack = new ArrayDeque<>();
        for (Order order : orderDAO.findByUser(customer.getUserId())) {   // oldest -> newest
            stack.push(order);                                            // newest ends up on top
        }
        return stack;
    }
}
