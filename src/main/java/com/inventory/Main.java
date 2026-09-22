package com.inventory;

import com.inventory.dao.OrderDAO;
import com.inventory.dao.ProductDAO;
import com.inventory.dao.UserDAO;
import com.inventory.exception.DatabaseException;
import com.inventory.exception.InsufficientStockException;
import com.inventory.exception.InvalidUserException;
import com.inventory.exception.OrderProcessingException;
import com.inventory.exception.ProductNotFoundException;
import com.inventory.model.Order;
import com.inventory.model.OrderItem;
import com.inventory.model.Product;
import com.inventory.model.User;
import com.inventory.service.OrderService;
import com.inventory.service.ProductService;
import com.inventory.service.UserService;
import com.inventory.util.DBConnection;

import java.math.BigDecimal;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Console entry point: a login loop, then a role-specific menu loop.
 *
 * This class deliberately contains NO business logic and NO SQL - it only reads input, calls
 * a service method, and prints the result or the error message from a caught exception. Every
 * exception that a service can throw is caught here, so a bad menu choice or a typo in an
 * amount never crashes the program; it just prints a message and redraws the menu.
 */
public class Main {

    private final Scanner in = new Scanner(System.in);
    private final UserService userService;
    private final ProductService productService;
    private final OrderService orderService;

    private User currentUser;

    public static void main(String[] args) {
        System.out.println("Inventory & Order Management System");
        System.out.println("------------------------------------");
        try {
            DBConnection.initializeDatabase();
        } catch (DatabaseException e) {
            // The one failure we truly cannot recover from: without a database there is
            // nothing the rest of the program can do.
            System.out.println("Could not start: " + e.getMessage());
            return;
        }
        new Main().run();
    }

    public Main() {
        ProductDAO productDAO = new ProductDAO();
        this.userService = new UserService(new UserDAO());
        this.productService = new ProductService(productDAO);
        this.orderService = new OrderService(new OrderDAO(productDAO), productService);
    }

    private void run() {
        try {
            while (true) {
                if (currentUser == null) {
                    if (!loginMenu()) {
                        break;                               // user chose to exit
                    }
                    continue;
                }
                if (currentUser.isAdmin()) {
                    adminMenu();
                } else {
                    customerMenu();
                }
            }
        } catch (java.util.NoSuchElementException | IllegalStateException e) {
            // Input closed (Ctrl+D / redirected input ran out) mid-prompt: exit cleanly
            // instead of printing a stack trace, per "never let the program crash".
            System.out.println("\nInput closed.");
        }
        System.out.println("\nGoodbye!");
        in.close();
    }

    // ==================================================================== login

    /** @return false if the user chose to exit the whole program */
    private boolean loginMenu() {
        System.out.println("\n1. Login   2. Exit");
        switch (readChoice(1, 2)) {
            case 1 -> login();
            case 2 -> { return false; }
        }
        return true;
    }

    private void login() {
        System.out.print("Username: ");
        String username = in.nextLine();
        System.out.print("Password: ");
        String password = in.nextLine();
        try {
            currentUser = userService.login(username, password);
            System.out.println("Welcome, " + currentUser.getUsername() + " (" + currentUser.getRole() + ")");
        } catch (InvalidUserException e) {
            System.out.println("Login failed: " + e.getMessage());
        }
    }

    // ==================================================================== admin

    private void adminMenu() {
        System.out.println("\n--- Admin menu (" + currentUser.getUsername() + ") ---");
        System.out.println(" 1. View all products");
        System.out.println(" 2. Add product");
        System.out.println(" 3. Update product");
        System.out.println(" 4. Delete product");
        System.out.println(" 5. Sort products by price");
        System.out.println(" 6. Sort products by stock");
        System.out.println(" 7. Search products");
        System.out.println(" 8. Low-stock alerts");
        System.out.println(" 9. View pending orders (processing queue)");
        System.out.println("10. Process next order");
        System.out.println("11. Logout");

        try {
            switch (readChoice(1, 11)) {
                case 1 -> printProducts(productService.getAllProducts());
                case 2 -> addProduct();
                case 3 -> updateProduct();
                case 4 -> deleteProduct();
                case 5 -> printProducts(productService.getProductsSortedByPrice(askAscending()));
                case 6 -> printProducts(productService.getProductsSortedByStock(askAscending()));
                case 7 -> searchProducts();
                case 8 -> printProducts(productService.getLowStockAlerts(currentUser, ProductService.LOW_STOCK_THRESHOLD));
                case 9 -> printOrders(orderService.getPendingOrders(currentUser));
                case 10 -> processNextOrder();
                case 11 -> currentUser = null;
            }
        } catch (InvalidUserException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (DatabaseException e) {
            System.out.println("A database error occurred: " + e.getMessage());
        }
    }

    private void addProduct() throws InvalidUserException {
        System.out.print("Name: ");
        String name = in.nextLine();
        System.out.print("Category: ");
        String category = in.nextLine();
        BigDecimal price = readPrice();
        int stock = readInt("Initial stock quantity: ", 0, Integer.MAX_VALUE);
        try {
            Product added = productService.addProduct(currentUser, name, category, price, stock);
            System.out.println("Added " + added);
        } catch (IllegalArgumentException e) {
            System.out.println("Could not add product: " + e.getMessage());
        }
    }

    private void updateProduct() throws InvalidUserException {
        int id = readInt("Product id to update: ", 1, Integer.MAX_VALUE);
        try {
            Product existing = productService.getProduct(id);
            System.out.println("Current: " + existing);
            System.out.print("New name [" + existing.getName() + "]: ");
            String name = orDefault(in.nextLine(), existing.getName());
            System.out.print("New category [" + existing.getCategory() + "]: ");
            String category = orDefault(in.nextLine(), existing.getCategory());
            System.out.print("New price [" + existing.getPrice() + "] (blank to keep): ");
            String priceLine = in.nextLine();
            BigDecimal price = priceLine.isBlank() ? existing.getPrice() : parsePrice(priceLine);
            System.out.print("New stock [" + existing.getStockQuantity() + "] (blank to keep): ");
            String stockLine = in.nextLine();
            int stock = stockLine.isBlank() ? existing.getStockQuantity() : Integer.parseInt(stockLine.trim());

            Product updated = productService.updateProduct(currentUser, id, name, category, price, stock);
            System.out.println("Updated " + updated);
        } catch (ProductNotFoundException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("Could not update product: " + e.getMessage());
        }
    }

    private void deleteProduct() throws InvalidUserException {
        int id = readInt("Product id to delete: ", 1, Integer.MAX_VALUE);
        try {
            productService.deleteProduct(currentUser, id);
            System.out.println("Product " + id + " deleted.");
        } catch (ProductNotFoundException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void processNextOrder() throws InvalidUserException {
        orderService.processNextOrder(currentUser)
                .ifPresentOrElse(
                        order -> System.out.println("Processed " + describeOrder(order)),
                        () -> System.out.println("No pending orders."));
    }

    // ================================================================= customer

    private void customerMenu() {
        System.out.println("\n--- Customer menu (" + currentUser.getUsername() + ") ---");
        System.out.println("1. View all products");
        System.out.println("2. Search products");
        System.out.println("3. Place an order");
        System.out.println("4. View my order history");
        System.out.println("5. Logout");

        try {
            switch (readChoice(1, 5)) {
                case 1 -> printProducts(productService.getAllProducts());
                case 2 -> searchProducts();
                case 3 -> placeOrder();
                case 4 -> printHistory(orderService.getOrderHistory(currentUser));
                case 5 -> currentUser = null;
            }
        } catch (InvalidUserException e) {
            System.out.println("Error: " + e.getMessage());
        } catch (DatabaseException e) {
            System.out.println("A database error occurred: " + e.getMessage());
        }
    }

    private void placeOrder() throws InvalidUserException {
        Map<Integer, Integer> cart = new LinkedHashMap<>();
        System.out.println("Building your order. Enter product id 0 when done.");
        while (true) {
            printProducts(productService.getAllProducts());
            int productId = readInt("Product id (0 to finish): ", 0, Integer.MAX_VALUE);
            if (productId == 0) {
                break;
            }
            int quantity = readInt("Quantity: ", 1, Integer.MAX_VALUE);
            cart.merge(productId, quantity, Integer::sum);   // ordering the same product twice adds up
            System.out.println("Added to cart. (" + cart.size() + " distinct product(s) so far)");
        }
        if (cart.isEmpty()) {
            System.out.println("Order cancelled: cart is empty.");
            return;
        }
        try {
            Order order = orderService.placeOrder(currentUser, cart);
            System.out.println("Order placed: " + describeOrder(order));
        } catch (InsufficientStockException | ProductNotFoundException | OrderProcessingException e) {
            System.out.println("Order could not be placed: " + e.getMessage());
        }
    }

    // ============================================================== shared UI

    private void searchProducts() {
        System.out.println("1. By exact name   2. By name prefix   3. By category");
        List<Product> results = switch (readChoice(1, 3)) {
            case 1 -> productService.searchByName(readLine("Name: "));
            case 2 -> productService.searchByNamePrefix(readLine("Name starts with: "));
            case 3 -> productService.searchByCategory(readLine("Category: "));
            default -> List.of();
        };
        printProducts(results);
    }

    private void printProducts(List<Product> products) {
        if (products.isEmpty()) {
            System.out.println("(no products to show)");
            return;
        }
        System.out.printf("%-4s %-24s %-14s %10s %7s%n", "ID", "Name", "Category", "Price", "Stock");
        for (Product p : products) {
            System.out.printf("%-4d %-24s %-14s %10s %7d%n",
                    p.getProductId(), p.getName(), p.getCategory(), p.getPrice(), p.getStockQuantity());
        }
    }

    private void printOrders(List<Order> orders) {
        if (orders.isEmpty()) {
            System.out.println("(no orders to show)");
            return;
        }
        for (Order order : orders) {
            System.out.println(describeOrder(order));
        }
    }

    private void printHistory(Deque<Order> history) {
        if (history.isEmpty()) {
            System.out.println("You have not placed any orders yet.");
            return;
        }
        System.out.println("Your orders, most recent first:");
        for (Order order : history) {                        // Deque iterates top (newest) to bottom
            System.out.println(describeOrder(order));
        }
    }

    private String describeOrder(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("Order #").append(order.getOrderId())
                .append(" [").append(order.getStatus()).append("] ")
                .append(order.getOrderDate()).append(" - total ").append(order.getTotalAmount());
        for (OrderItem item : order.getItems()) {
            sb.append("\n    - ").append(item);
        }
        return sb.toString();
    }

    // ============================================================= input helpers

    private int readChoice(int min, int max) {
        return readInt("Choice: ", min, max);
    }

    /** Reads an integer in [min, max], reprompting on anything else (including blank/non-numeric input). */
    private int readInt(String prompt, int min, int max) {
        while (true) {
            System.out.print(prompt);
            String line = in.nextLine().trim();
            try {
                int value = Integer.parseInt(line);
                if (value >= min && value <= max) {
                    return value;
                }
                System.out.println("Please enter a number between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                System.out.println("Please enter a valid whole number.");
            }
        }
    }

    private BigDecimal readPrice() {
        while (true) {
            System.out.print("Price: ");
            try {
                return parsePrice(in.nextLine());
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
    }

    /** Accepts up to 2 decimal places; rejects blank, negative or overly precise input. */
    private BigDecimal parsePrice(String text) {
        try {
            BigDecimal price = new BigDecimal(text.trim());
            if (price.signum() < 0) {
                throw new IllegalArgumentException("Price must not be negative.");
            }
            if (price.scale() > 2) {
                throw new IllegalArgumentException("Price can have at most 2 decimal places.");
            }
            return price.setScale(2);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Please enter a valid price, e.g. 199.99");
        }
    }

    private boolean askAscending() {
        System.out.print("1. Ascending   2. Descending: ");
        return readChoice(1, 2) == 1;
    }

    private String readLine(String prompt) {
        System.out.print(prompt);
        return in.nextLine();
    }

    private String orDefault(String input, String defaultValue) {
        return input.isBlank() ? defaultValue : input.trim();
    }
}
