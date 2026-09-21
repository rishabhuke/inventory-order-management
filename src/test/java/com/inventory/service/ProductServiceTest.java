package com.inventory.service;

import com.inventory.dao.ProductDAO;
import com.inventory.exception.InvalidUserException;
import com.inventory.exception.ProductNotFoundException;
import com.inventory.model.Product;
import com.inventory.model.Role;
import com.inventory.model.User;
import com.inventory.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against a real (throw-away) SQLite database holding the 12 sample products from schema.sql. */
class ProductServiceTest {

    private static final User ADMIN = new User(1, "admin", Role.ADMIN);
    private static final User CUSTOMER = new User(2, "alice", Role.CUSTOMER);

    private ProductService service;

    @BeforeEach
    void freshDatabase() {
        TestDatabase.reset();
        service = new ProductService(new ProductDAO());
    }

    private static List<Integer> ids(List<Product> products) {
        return products.stream().map(Product::getProductId).toList();
    }

    // --------------------------------------------------------------- cache

    @Test
    @DisplayName("cache is loaded from the database and looks products up by id")
    void cacheLoadsAndLooksUp() throws Exception {
        assertEquals(12, service.getAllProducts().size());
        assertEquals("Wireless Mouse", service.getProduct(1).getName());
        assertThrows(ProductNotFoundException.class, () -> service.getProduct(999));
    }

    @Test
    @DisplayName("addProduct saves to the database and to the cache")
    void addProductPersists() throws Exception {
        Product added = service.addProduct(ADMIN, "Monitor Arm", "Accessories", new BigDecimal("2500"), 6);

        assertTrue(added.getProductId() > 12);
        assertEquals(0, new BigDecimal("2500.00").compareTo(service.getProduct(added.getProductId()).getPrice()));
        // A brand-new service instance reads the database, proving it was really saved.
        assertEquals("Monitor Arm", new ProductService(new ProductDAO()).getProduct(added.getProductId()).getName());
    }

    @Test
    @DisplayName("updateProduct changes database and cache")
    void updateProductPersists() throws Exception {
        service.updateProduct(ADMIN, 1, "Wireless Mouse Pro", "Electronics", new BigDecimal("899.50"), 40);

        Product fromCache = service.getProduct(1);
        assertEquals("Wireless Mouse Pro", fromCache.getName());
        assertEquals(40, fromCache.getStockQuantity());
        assertEquals(40, new ProductService(new ProductDAO()).getProduct(1).getStockQuantity());
    }

    @Test
    @DisplayName("deleteProduct hides the product everywhere")
    void deleteProductHidesIt() throws Exception {
        service.deleteProduct(ADMIN, 2);

        assertThrows(ProductNotFoundException.class, () -> service.getProduct(2));
        assertEquals(11, service.getAllProducts().size());
        assertThrows(ProductNotFoundException.class, () -> new ProductService(new ProductDAO()).getProduct(2));
        assertThrows(ProductNotFoundException.class, () -> service.deleteProduct(ADMIN, 2));
    }

    @Test
    @DisplayName("invalid input is rejected and nothing is saved")
    void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addProduct(ADMIN, "  ", "Cat", BigDecimal.TEN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.addProduct(ADMIN, "Thing", "Cat", new BigDecimal("-1"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.addProduct(ADMIN, "Thing", "Cat", new BigDecimal("1.999"), 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.addProduct(ADMIN, "Thing", "Cat", BigDecimal.TEN, -5));
        assertEquals(12, service.getAllProducts().size());
    }

    @Test
    @DisplayName("customers cannot add, update, delete or see stock alerts")
    void adminOnlyOperations() {
        assertThrows(InvalidUserException.class,
                () -> service.addProduct(CUSTOMER, "X", "Y", BigDecimal.ONE, 1));
        assertThrows(InvalidUserException.class,
                () -> service.updateProduct(CUSTOMER, 1, "X", "Y", BigDecimal.ONE, 1));
        assertThrows(InvalidUserException.class, () -> service.deleteProduct(CUSTOMER, 1));
        assertThrows(InvalidUserException.class, () -> service.getLowStockAlerts(CUSTOMER, 10));
        assertThrows(InvalidUserException.class, () -> service.addProduct(null, "X", "Y", BigDecimal.ONE, 1));
        assertEquals(12, service.getAllProducts().size());
    }

    // ------------------------------------------------------------- sorting

    @Test
    @DisplayName("sorts by price ascending and descending")
    void sortByPrice() {
        List<Product> asc = service.getProductsSortedByPrice(true);
        assertEquals(10, asc.get(0).getProductId());          // Sticky Notes 59.00
        assertEquals(2, asc.get(11).getProductId());          // Mechanical Keyboard 3499.00
        assertEquals(2, service.getProductsSortedByPrice(false).get(0).getProductId());
    }

    @Test
    @DisplayName("sorts by stock ascending and descending")
    void sortByStock() {
        assertEquals(4, service.getProductsSortedByStock(true).get(0).getProductId());   // Webcam, stock 2
        assertEquals(8, service.getProductsSortedByStock(false).get(0).getProductId());  // Notebook, stock 200
    }

    // ------------------------------------------------------------ searching

    @Test
    @DisplayName("exact name search is case-insensitive")
    void exactNameSearch() {
        assertEquals(List.of(1), ids(service.searchByName("wireless MOUSE")));
        assertTrue(service.searchByName("Wireless").isEmpty());          // exact, not "contains"
        assertTrue(service.searchByName("nothing like this").isEmpty());
        assertTrue(service.searchByName("  ").isEmpty());
        assertTrue(service.searchByName(null).isEmpty());
    }

    @Test
    @DisplayName("prefix search returns matches in name order, case-insensitively")
    void prefixSearch() {
        assertEquals(List.of(1), ids(service.searchByNamePrefix("wir")));
        assertEquals(List.of(12), ids(service.searchByNamePrefix("WAT")));
        assertEquals(List.of(10), ids(service.searchByNamePrefix("s")));
        // Two names start with "w": "Water Bottle" (id 12) sorts before "Wireless Mouse" (id 1).
        assertEquals(List.of(12, 1), ids(service.searchByNamePrefix("w")));
        assertTrue(service.searchByNamePrefix("zzz").isEmpty());
    }

    @Test
    @DisplayName("category search is case-insensitive")
    void categorySearch() {
        assertEquals(4, service.searchByCategory("electronics").size());
        assertEquals(3, service.searchByCategory(" STATIONERY ").size());
        assertTrue(service.searchByCategory("Furniture").isEmpty());
    }

    @Test
    @DisplayName("search indexes are rebuilt after changes (no stale results)")
    void indexesStayFresh() throws Exception {
        assertTrue(service.searchByName("Monitor Arm").isEmpty());
        assertEquals(4, service.searchByCategory("Electronics").size());

        service.addProduct(ADMIN, "Monitor Arm", "Electronics", new BigDecimal("2500.00"), 6);
        assertEquals(1, service.searchByName("monitor arm").size());
        assertEquals(5, service.searchByCategory("Electronics").size());

        service.deleteProduct(ADMIN, 1);
        assertTrue(service.searchByName("Wireless Mouse").isEmpty());
        assertEquals(4, service.searchByCategory("Electronics").size());

        service.updateProduct(ADMIN, 2, "Mechanical Keyboard", "Furniture", new BigDecimal("3499.00"), 25);
        assertEquals(3, service.searchByCategory("Electronics").size());
        assertEquals(1, service.searchByCategory("Furniture").size());
    }

    // ------------------------------------------------------ low-stock alerts

    @Test
    @DisplayName("low-stock alert lists products at or below the threshold, lowest stock first")
    void lowStockAlertOrder() throws Exception {
        // Sample stocks <= 10: Webcam 2 (id4), Water Bottle 3 (id12), Gel Pen 4 (id9), Sticky Notes 6 (id10), USB-C Hub 8 (id3)
        assertEquals(List.of(4, 12, 9, 10, 3), ids(service.getLowStockAlerts(ADMIN, ProductService.LOW_STOCK_THRESHOLD)));
    }

    @Test
    @DisplayName("low-stock alert respects the threshold and breaks ties by id")
    void lowStockAlertThresholdAndTies() throws Exception {
        assertEquals(List.of(4, 12), ids(service.getLowStockAlerts(ADMIN, 3)));
        assertTrue(service.getLowStockAlerts(ADMIN, 1).isEmpty());

        service.updateProduct(ADMIN, 5, "Laptop Stand", "Accessories", new BigDecimal("1499.00"), 2);
        assertEquals(List.of(4, 5, 12), ids(service.getLowStockAlerts(ADMIN, 3)));   // 4 and 5 both have stock 2 -> id order
    }
}
