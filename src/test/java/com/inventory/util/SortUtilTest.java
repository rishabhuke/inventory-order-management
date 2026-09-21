package com.inventory.util;

import com.inventory.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SortUtilTest {

    private static List<Integer> sorted(Integer... values) {
        return SortUtil.mergeSort(List.of(values), Comparator.naturalOrder());
    }

    private static Product product(int id, String name, String price, int stock) {
        return new Product(id, name, "Cat", new BigDecimal(price), stock);
    }

    @Test
    @DisplayName("sorts an unsorted list ascending")
    void sortsUnsortedList() {
        assertEquals(List.of(1, 2, 3, 5, 8, 9), sorted(5, 2, 9, 1, 8, 3));
    }

    @Test
    @DisplayName("handles empty and single-element lists")
    void handlesTrivialLists() {
        assertEquals(List.of(), sorted());
        assertEquals(List.of(7), sorted(7));
    }

    @Test
    @DisplayName("handles already-sorted, reverse-sorted and all-equal input")
    void handlesSpecialShapes() {
        assertEquals(List.of(1, 2, 3, 4), sorted(1, 2, 3, 4));
        assertEquals(List.of(1, 2, 3, 4), sorted(4, 3, 2, 1));
        assertEquals(List.of(5, 5, 5), sorted(5, 5, 5));
    }

    @Test
    @DisplayName("handles duplicates and negative numbers")
    void handlesDuplicatesAndNegatives() {
        assertEquals(List.of(-3, -3, 0, 2, 2, 10), sorted(2, -3, 10, 0, 2, -3));
    }

    @Test
    @DisplayName("does not modify the input list")
    void doesNotMutateInput() {
        List<Integer> input = new ArrayList<>(List.of(3, 1, 2));
        SortUtil.mergeSort(input, Comparator.naturalOrder());
        assertEquals(List.of(3, 1, 2), input);
    }

    @Test
    @DisplayName("is stable: equal keys keep their original relative order")
    void isStable() {
        record Item(int key, String label) { }
        List<Item> input = List.of(
                new Item(2, "first-2"), new Item(1, "first-1"), new Item(2, "second-2"),
                new Item(1, "second-1"), new Item(2, "third-2"));

        List<Item> result = SortUtil.mergeSort(input, Comparator.comparingInt(Item::key));

        assertEquals(List.of("first-1", "second-1", "first-2", "second-2", "third-2"),
                result.stream().map(Item::label).toList());
    }

    @Test
    @DisplayName("agrees with the JDK sort on 500 random lists (oracle test)")
    void matchesJdkSortOnRandomInput() {
        Random random = new Random(42);              // fixed seed -> reproducible
        for (int trial = 0; trial < 500; trial++) {
            int size = random.nextInt(60);
            List<Integer> input = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                input.add(random.nextInt(20));       // small range -> plenty of duplicates
            }
            List<Integer> expected = new ArrayList<>(input);
            Collections.sort(expected);              // allowed here: it is only the test oracle

            assertEquals(expected, SortUtil.mergeSort(input, Comparator.naturalOrder()),
                    "failed for input " + input);
        }
    }

    @Test
    @DisplayName("sorts products by price ascending and descending")
    void sortsProductsByPrice() {
        List<Product> products = List.of(
                product(1, "A", "300.00", 5), product(2, "B", "50.50", 5), product(3, "C", "120.00", 5));

        assertEquals(List.of(2, 3, 1),
                SortUtil.mergeSort(products, SortUtil.BY_PRICE).stream().map(Product::getProductId).toList());
        assertEquals(List.of(1, 3, 2),
                SortUtil.mergeSort(products, SortUtil.BY_PRICE.reversed()).stream().map(Product::getProductId).toList());
    }

    @Test
    @DisplayName("sorts products by stock; equal stock keeps id order (stability)")
    void sortsProductsByStock() {
        List<Product> products = List.of(
                product(1, "A", "1.00", 10), product(2, "B", "1.00", 3),
                product(3, "C", "1.00", 10), product(4, "D", "1.00", 0));

        assertEquals(List.of(4, 2, 1, 3),
                SortUtil.mergeSort(products, SortUtil.BY_STOCK).stream().map(Product::getProductId).toList());
    }

    @Test
    @DisplayName("sorts products by name case-insensitively")
    void sortsProductsByName() {
        List<Product> products = List.of(
                product(1, "banana", "1.00", 1), product(2, "Apple", "1.00", 1), product(3, "cherry", "1.00", 1));

        assertEquals(List.of("Apple", "banana", "cherry"),
                SortUtil.mergeSort(products, SortUtil.BY_NAME).stream().map(Product::getName).toList());
    }

    @Test
    @DisplayName("rejects null arguments")
    void rejectsNulls() {
        assertThrows(NullPointerException.class, () -> SortUtil.mergeSort(null, Comparator.<Integer>naturalOrder()));
        assertThrows(NullPointerException.class, () -> SortUtil.mergeSort(List.of(1), null));
    }
}
