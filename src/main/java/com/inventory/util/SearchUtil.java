package com.inventory.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Hand-written searching, in two flavours.
 *
 * 1) BINARY SEARCH on a list that is already sorted by the search key: O(log n) per search.
 *    Used for product NAME searches (exact and "starts with"). Precondition: the list must be
 *    sorted with the same ordering, e.g. SortUtil.mergeSort(products, SortUtil.BY_NAME).
 *    Binary search cannot answer "name CONTAINS text" - for that you would need a linear scan
 *    or a full-text index, so this project deliberately offers exact and prefix search only.
 *
 * 2) HASHMAP INDEX for CATEGORY lookups: O(n) to build once, then O(1) on average per lookup.
 *    A category is an exact-match key with many products per key, which is exactly what a
 *    HashMap<String, List<Product>> is for (binary search would work too, but a hash lookup
 *    is simpler and faster here).
 */
public final class SearchUtil {

    private SearchUtil() {
        // utility class - no instances
    }

    // ------------------------------------------------------------ binary search

    /**
     * Binary search, "leftmost" variant: returns the index of the FIRST element whose key is
     * greater than or equal to {@code key}, or {@code sorted.size()} if there is none.
     *
     * Every other search below is built on this one function. Returning the leftmost position
     * (rather than "any match") is what lets us find ALL duplicates and a whole prefix range:
     * they sit right next to each other starting at this index.
     *
     * Time: O(log n) - the search window [lo, hi) is halved on every iteration.
     * Space: O(1).
     *
     * @param sorted list sorted ascending by {@code order} applied to {@code keyOf}
     */
    public static <T, K> int lowerBound(List<T> sorted, K key,
                                        Function<? super T, ? extends K> keyOf,
                                        Comparator<? super K> order) {
        int lo = 0;
        int hi = sorted.size();                      // answer is always somewhere in [lo, hi]
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (order.compare(keyOf.apply(sorted.get(mid)), key) < 0) {
                lo = mid + 1;                        // mid is too small: answer is to the right
            } else {
                hi = mid;                            // mid could be the answer: keep it in range
            }
        }
        return lo;
    }

    /**
     * All elements whose key EQUALS {@code key} (case-insensitive), in list order.
     * O(log n) to find the first match, plus O(k) to collect the k matches.
     *
     * @param sortedByKey sorted ascending by the key, case-insensitively
     */
    public static <T> List<T> findAllEqual(List<T> sortedByKey, String key,
                                           Function<? super T, String> keyOf) {
        Objects.requireNonNull(key, "key must not be null");
        List<T> matches = new ArrayList<>();
        int i = lowerBound(sortedByKey, key, keyOf, String.CASE_INSENSITIVE_ORDER);
        while (i < sortedByKey.size()
                && String.CASE_INSENSITIVE_ORDER.compare(keyOf.apply(sortedByKey.get(i)), key) == 0) {
            matches.add(sortedByKey.get(i));
            i++;
        }
        return matches;
    }

    /**
     * All elements whose key STARTS WITH {@code prefix} (case-insensitive).
     *
     * In a sorted list, everything that starts with a given prefix forms one contiguous block,
     * and that block begins at lowerBound(prefix) (a string is always >= its own prefix).
     * So: one binary search to find the block start, then walk forward until the prefix stops
     * matching. O(log n + k) for k results, versus O(n) for scanning the whole list.
     * An empty prefix matches everything.
     *
     * @param sortedByKey sorted ascending by the key, case-insensitively
     */
    public static <T> List<T> findAllWithPrefix(List<T> sortedByKey, String prefix,
                                                Function<? super T, String> keyOf) {
        Objects.requireNonNull(prefix, "prefix must not be null");
        List<T> matches = new ArrayList<>();
        int i = lowerBound(sortedByKey, prefix, keyOf, String.CASE_INSENSITIVE_ORDER);
        while (i < sortedByKey.size()
                && keyOf.apply(sortedByKey.get(i)).regionMatches(true, 0, prefix, 0, prefix.length())) {
            matches.add(sortedByKey.get(i));
            i++;
        }
        return matches;
    }

    // ------------------------------------------------------------ hash index

    /**
     * Groups items by key into a HashMap so they can be looked up in O(1) on average.
     * Keys are trimmed and lower-cased, so "Electronics" and " electronics " are the same key.
     * Build cost: O(n). Rebuild whenever the underlying data changes.
     */
    public static <T> Map<String, List<T>> buildIndex(Collection<T> items,
                                                      Function<? super T, String> keyOf) {
        Map<String, List<T>> index = new HashMap<>();
        for (T item : items) {
            index.computeIfAbsent(normalize(keyOf.apply(item)), k -> new ArrayList<>()).add(item);
        }
        return index;
    }

    /** O(1) average lookup in an index made by buildIndex. Never returns null. */
    public static <T> List<T> lookup(Map<String, List<T>> index, String key) {
        if (key == null) {
            return new ArrayList<>();
        }
        List<T> found = index.get(normalize(key));
        return found == null ? new ArrayList<>() : new ArrayList<>(found);
    }

    private static String normalize(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
