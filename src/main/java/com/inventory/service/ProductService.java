package com.inventory.service;

import com.inventory.dao.ProductDAO;
import com.inventory.exception.InvalidUserException;
import com.inventory.exception.ProductNotFoundException;
import com.inventory.model.Product;
import com.inventory.model.User;
import com.inventory.util.SearchUtil;
import com.inventory.util.SortUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Product business logic plus the in-memory layers on top of the database:
 *
 *  1. CACHE  - HashMap<Integer, Product>: lookup by product id in O(1) without touching the DB.
 *  2. NAME INDEX - the products sorted by name, so name searches are O(log n) binary searches.
 *  3. CATEGORY INDEX - HashMap<String, List<Product>>: category lookups in O(1) on average.
 *
 * KEEPING THEM IN SYNC: every write goes to the DATABASE FIRST. Only when the database has
 * accepted the change is the cache updated, so an exception can never leave the cache claiming
 * something the database does not have. After any change the two indexes are thrown away and
 * rebuilt lazily on the next search (O(n log n) once per change, then cheap searches).
 *
 * LIMITS (worth stating in the README): one process, one thread. If another process changed the
 * same database file, this cache would not know. The order transaction is still safe because the
 * DAO re-checks stock inside the database (see ProductDAO.deductStockIfAvailable).
 *
 * Products returned from here are the cached objects themselves: callers must treat them as
 * READ-ONLY and change products only through this service.
 */
public class ProductService {

    /** Products with this much stock or less appear in the low-stock alert. */
    public static final int LOW_STOCK_THRESHOLD = 10;

    private static final Comparator<Product> BY_ID = Comparator.comparingInt(Product::getProductId);

    private final ProductDAO productDAO;
    private final Map<Integer, Product> cache = new HashMap<>();

    // Lazily rebuilt search indexes; null means "stale, rebuild before use".
    private List<Product> nameSortedIndex;
    private Map<String, List<Product>> categoryIndex;

    public ProductService(ProductDAO productDAO) {
        this.productDAO = productDAO;
        loadCache();
    }

    // ---------------------------------------------------------- cache handling

    /** Throws the cache away and reloads every active product from the database. */
    public void reloadAll() {
        loadCache();
    }

    private void loadCache() {
        cache.clear();
        for (Product product : productDAO.findAllActive()) {
            cache.put(product.getProductId(), product);
        }
        invalidateIndexes();
    }

    /**
     * Re-reads ONE product from the database into the cache (or drops it if it no longer exists).
     * OrderService calls this after every order attempt so stock levels in the cache always
     * come from the database instead of being adjusted by hand.
     */
    public void refreshProduct(int productId) {
        productDAO.findById(productId).ifPresentOrElse(
                product -> cache.put(productId, product),
                () -> cache.remove(productId));
        invalidateIndexes();
    }

    private void invalidateIndexes() {
        nameSortedIndex = null;
        categoryIndex = null;
    }

    private void ensureIndexes() {
        if (nameSortedIndex == null) {
            // Start from an id-sorted list so products with equal names stay in id order
            // (merge sort is stable).
            List<Product> byId = SortUtil.mergeSort(new ArrayList<>(cache.values()), BY_ID);
            nameSortedIndex = SortUtil.mergeSort(byId, SortUtil.BY_NAME);
            categoryIndex = SearchUtil.buildIndex(byId, Product::getCategory);
        }
    }

    // -------------------------------------------------------- admin operations

    public Product addProduct(User actor, String name, String category, BigDecimal price, int stock)
            throws InvalidUserException {
        UserService.requireAdmin(actor);
        Product candidate = new Product(name, category, normalizePrice(price), stock); // validates input
        Product saved = productDAO.insert(candidate);                                  // DB first ...
        cache.put(saved.getProductId(), saved);                                        // ... then cache
        invalidateIndexes();
        return saved;
    }

    public Product updateProduct(User actor, int productId, String name, String category,
                                 BigDecimal price, int stock)
            throws InvalidUserException, ProductNotFoundException {
        UserService.requireAdmin(actor);
        getProduct(productId);                                                         // must exist
        Product updated = new Product(productId, name, category, normalizePrice(price), stock);
        if (!productDAO.update(updated)) {                                             // DB first ...
            cache.remove(productId);                                                   // cache was stale
            invalidateIndexes();
            throw new ProductNotFoundException(productId);
        }
        cache.put(productId, updated);                                                 // ... then cache
        invalidateIndexes();
        return updated;
    }

    /** Soft delete: the product disappears from the catalogue but stays in old orders. */
    public void deleteProduct(User actor, int productId)
            throws InvalidUserException, ProductNotFoundException {
        UserService.requireAdmin(actor);
        getProduct(productId);
        if (!productDAO.softDelete(productId)) {
            cache.remove(productId);
            invalidateIndexes();
            throw new ProductNotFoundException(productId);
        }
        cache.remove(productId);
        invalidateIndexes();
    }

    // ---------------------------------------------------------------- lookups

    /** O(1) lookup by id, straight from the HashMap cache. */
    public Product getProduct(int productId) throws ProductNotFoundException {
        Product product = cache.get(productId);
        if (product == null) {
            throw new ProductNotFoundException(productId);
        }
        return product;
    }

    /** All active products in id order. */
    public List<Product> getAllProducts() {
        return SortUtil.mergeSort(new ArrayList<>(cache.values()), BY_ID);
    }

    // ---------------------------------------------------------------- sorting

    /** Sorted with our own merge sort, starting from id order so equal prices stay in id order. */
    public List<Product> getProductsSortedByPrice(boolean ascending) {
        return SortUtil.mergeSort(getAllProducts(),
                ascending ? SortUtil.BY_PRICE : SortUtil.BY_PRICE.reversed());
    }

    public List<Product> getProductsSortedByStock(boolean ascending) {
        return SortUtil.mergeSort(getAllProducts(),
                ascending ? SortUtil.BY_STOCK : SortUtil.BY_STOCK.reversed());
    }

    // ---------------------------------------------------------------- searching

    /** Products whose name equals the text (ignoring case). Binary search: O(log n + k). */
    public List<Product> searchByName(String name) {
        if (name == null || name.isBlank()) {
            return new ArrayList<>();
        }
        ensureIndexes();
        return SearchUtil.findAllEqual(nameSortedIndex, name.trim(), Product::getName);
    }

    /** Products whose name starts with the text (ignoring case). Binary search: O(log n + k). */
    public List<Product> searchByNamePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return new ArrayList<>();
        }
        ensureIndexes();
        return SearchUtil.findAllWithPrefix(nameSortedIndex, prefix.trim(), Product::getName);
    }

    /** Products in a category (ignoring case). HashMap lookup: O(1) on average. */
    public List<Product> searchByCategory(String category) {
        ensureIndexes();
        return SearchUtil.lookup(categoryIndex, category);
    }

    // -------------------------------------------------------- low-stock alerts

    /**
     * Products at or below the threshold, MOST CRITICAL (lowest stock) FIRST.
     *
     * Uses a PriorityQueue, which is a binary MIN-HEAP: offer() and poll() cost O(log n) and
     * the smallest element is always at the top. So the emptiest product is always the next
     * one out, no matter in what order products were added. Ties on stock are broken by id so
     * the output is deterministic (a heap on its own is not stable).
     *
     * Total cost for k alerts: O(k log k). (For a one-off full report a sort gives the same
     * result; the heap is the right structure when alerts arrive continuously or only the top
     * few are needed, which is why this project models it as a heap.)
     */
    public List<Product> getLowStockAlerts(User actor, int threshold) throws InvalidUserException {
        UserService.requireAdmin(actor);

        PriorityQueue<Product> minHeap = new PriorityQueue<>(SortUtil.BY_STOCK.thenComparing(BY_ID));
        for (Product product : cache.values()) {
            if (product.getStockQuantity() <= threshold) {
                minHeap.offer(product);                       // O(log n)
            }
        }

        List<Product> mostCriticalFirst = new ArrayList<>(minHeap.size());
        while (!minHeap.isEmpty()) {
            mostCriticalFirst.add(minHeap.poll());            // O(log n), always the lowest stock left
        }
        return mostCriticalFirst;
    }

    // ---------------------------------------------------------------- helpers

    /** Prices have at most 2 decimals; more than that is rejected rather than silently rounded. */
    private static BigDecimal normalizePrice(BigDecimal price) {
        if (price == null) {
            throw new IllegalArgumentException("Price must not be empty");
        }
        try {
            return price.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Price can have at most 2 decimal places");
        }
    }
}
