package com.inventory.util;

import com.inventory.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchUtilTest {

    private static final Comparator<Integer> NATURAL = Comparator.naturalOrder();

    private static int lowerBound(List<Integer> sorted, int key) {
        return SearchUtil.lowerBound(sorted, key, x -> x, NATURAL);
    }

    private static Product product(int id, String name, String category) {
        return new Product(id, name, category, new BigDecimal("10.00"), 5);
    }

    /** Products sorted by name - the precondition for the binary-search methods. */
    private static List<Product> catalogSortedByName() {
        return SortUtil.mergeSort(List.of(
                product(1, "Wireless Mouse", "Electronics"),
                product(2, "Wired Keyboard", "Electronics"),
                product(3, "Notebook A5", "Stationery"),
                product(4, "notebook a4", "Stationery"),
                product(5, "Gel Pen", "Stationery"),
                product(6, "Wireless Charger", "Electronics")), SortUtil.BY_NAME);
    }

    // ---------------------------------------------------------------- lowerBound

    @Test
    @DisplayName("lowerBound finds the first index of a present key")
    void lowerBoundFindsPresentKey() {
        List<Integer> data = List.of(1, 3, 5, 7, 9);
        assertEquals(0, lowerBound(data, 1));
        assertEquals(2, lowerBound(data, 5));
        assertEquals(4, lowerBound(data, 9));
    }

    @Test
    @DisplayName("lowerBound returns the FIRST index when the key is duplicated")
    void lowerBoundReturnsLeftmostDuplicate() {
        assertEquals(1, lowerBound(List.of(1, 4, 4, 4, 9), 4));
    }

    @Test
    @DisplayName("lowerBound returns the insertion point for absent keys")
    void lowerBoundForAbsentKeys() {
        List<Integer> data = List.of(10, 20, 30);
        assertEquals(0, lowerBound(data, 5));        // before everything
        assertEquals(1, lowerBound(data, 15));       // between elements
        assertEquals(3, lowerBound(data, 99));       // after everything -> size
    }

    @Test
    @DisplayName("lowerBound on an empty list returns 0")
    void lowerBoundOnEmptyList() {
        assertEquals(0, lowerBound(List.of(), 1));
    }

    @Test
    @DisplayName("lowerBound agrees with a linear scan on 500 random sorted lists")
    void lowerBoundMatchesLinearScan() {
        Random random = new Random(7);
        for (int trial = 0; trial < 500; trial++) {
            List<Integer> data = new ArrayList<>();
            int size = random.nextInt(40);
            for (int i = 0; i < size; i++) {
                data.add(random.nextInt(25));
            }
            data = SortUtil.mergeSort(data, NATURAL);
            int key = random.nextInt(30) - 2;

            int expected = 0;
            while (expected < data.size() && data.get(expected) < key) {
                expected++;
            }
            assertEquals(expected, lowerBound(data, key), "data=" + data + " key=" + key);
        }
    }

    // --------------------------------------------------------------- name search

    @Test
    @DisplayName("findAllEqual is case-insensitive and returns every duplicate name")
    void findAllEqualReturnsAllDuplicates() {
        List<Product> found = SearchUtil.findAllEqual(catalogSortedByName(), "NOTEBOOK A5", Product::getName);
        assertEquals(List.of(3), found.stream().map(Product::getProductId).toList());

        List<Product> twins = SortUtil.mergeSort(List.of(
                product(10, "Pen", "S"), product(11, "pen", "S"), product(12, "Pencil", "S")), SortUtil.BY_NAME);
        assertEquals(List.of(10, 11),
                SearchUtil.findAllEqual(twins, "PEN", Product::getName).stream().map(Product::getProductId).toList());
    }

    @Test
    @DisplayName("findAllEqual returns an empty list when nothing matches")
    void findAllEqualNoMatch() {
        assertTrue(SearchUtil.findAllEqual(catalogSortedByName(), "Keyboard", Product::getName).isEmpty());
        assertTrue(SearchUtil.findAllEqual(List.<Product>of(), "x", Product::getName).isEmpty());
    }

    @Test
    @DisplayName("findAllWithPrefix returns the whole contiguous block, case-insensitively")
    void findAllWithPrefixReturnsBlock() {
        List<Product> found = SearchUtil.findAllWithPrefix(catalogSortedByName(), "wire", Product::getName);
        assertEquals(List.of("Wired Keyboard", "Wireless Charger", "Wireless Mouse"),
                found.stream().map(Product::getName).toList());

        List<Product> notebooks = SearchUtil.findAllWithPrefix(catalogSortedByName(), "NoteBook", Product::getName);
        assertEquals(2, notebooks.size());
    }

    @Test
    @DisplayName("findAllWithPrefix: no hit, empty prefix, and prefix longer than any name")
    void findAllWithPrefixEdgeCases() {
        List<Product> catalog = catalogSortedByName();
        assertTrue(SearchUtil.findAllWithPrefix(catalog, "zzz", Product::getName).isEmpty());
        assertEquals(catalog.size(), SearchUtil.findAllWithPrefix(catalog, "", Product::getName).size());
        assertTrue(SearchUtil.findAllWithPrefix(catalog, "Wireless Mouse Deluxe Edition", Product::getName).isEmpty());
    }

    @Test
    @DisplayName("findAllWithPrefix agrees with a linear scan for every prefix of every name")
    void prefixSearchMatchesLinearScan() {
        List<Product> catalog = catalogSortedByName();
        for (Product p : catalog) {
            for (int len = 0; len <= p.getName().length(); len++) {
                String prefix = p.getName().substring(0, len);
                List<Integer> expected = catalog.stream()
                        .filter(x -> x.getName().toLowerCase().startsWith(prefix.toLowerCase()))
                        .map(Product::getProductId).toList();
                List<Integer> actual = SearchUtil.findAllWithPrefix(catalog, prefix, Product::getName)
                        .stream().map(Product::getProductId).toList();
                assertEquals(expected, actual, "prefix='" + prefix + "'");
            }
        }
    }

    // ------------------------------------------------------------ category index

    @Test
    @DisplayName("category index groups products and looks them up case-insensitively")
    void categoryIndexLookup() {
        Map<String, List<Product>> index = SearchUtil.buildIndex(catalogSortedByName(), Product::getCategory);

        assertEquals(3, SearchUtil.lookup(index, "electronics").size());
        assertEquals(3, SearchUtil.lookup(index, "  STATIONERY ").size());
        assertTrue(SearchUtil.lookup(index, "Furniture").isEmpty());
        assertTrue(SearchUtil.lookup(index, null).isEmpty());
    }

    @Test
    @DisplayName("lookup returns a copy, so callers cannot corrupt the index")
    void lookupReturnsCopy() {
        Map<String, List<Product>> index = SearchUtil.buildIndex(catalogSortedByName(), Product::getCategory);
        SearchUtil.lookup(index, "Electronics").clear();
        assertEquals(3, SearchUtil.lookup(index, "Electronics").size());
    }
}
