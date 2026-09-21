package com.inventory.util;

import com.inventory.model.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Hand-written sorting (no Collections.sort / List.sort / streams).
 *
 * WHY MERGE SORT (and not quick sort)?
 *   - O(n log n) in the best, average AND worst case. Quick sort degrades to O(n^2)
 *     on already-sorted input with a naive pivot, and a product list read from the
 *     database in id order is often nearly sorted.
 *   - STABLE: elements that compare equal keep their original relative order. That is
 *     what makes "sort by price" leave equally-priced products in id order, and lets
 *     several sorts be layered (sort by name, then by price).
 *   - Cost: O(n) extra memory for the scratch buffer, which is fine at this scale.
 */
public final class SortUtil {

    // Ready-made orderings for products. BY_NAME is case-insensitive and is the ordering
    // SearchUtil's binary search expects - keep the two in sync.
    public static final Comparator<Product> BY_PRICE =
            Comparator.comparing(Product::getPrice);
    public static final Comparator<Product> BY_STOCK =
            Comparator.comparingInt(Product::getStockQuantity);
    public static final Comparator<Product> BY_NAME =
            Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER);

    private SortUtil() {
        // utility class - no instances
    }

    /**
     * Returns a NEW list containing the elements of {@code input} in the order defined
     * by {@code comparator}. The input list is not modified.
     *
     * Time:  O(n log n) always - the list is halved log2(n) times and every level does
     *        O(n) merging work.
     * Space: O(n) for the copy plus the scratch buffer.
     * Stable.
     */
    public static <T> List<T> mergeSort(List<T> input, Comparator<? super T> comparator) {
        Objects.requireNonNull(input, "input list must not be null");
        Objects.requireNonNull(comparator, "comparator must not be null");

        List<T> working = new ArrayList<>(input);   // the list we sort in place
        List<T> scratch = new ArrayList<>(input);   // reusable buffer, allocated ONCE
        sort(working, scratch, 0, working.size() - 1, comparator);
        return working;
    }

    /** Sorts a[lo..hi] (inclusive): split in half, sort each half, merge the halves. */
    private static <T> void sort(List<T> a, List<T> scratch, int lo, int hi, Comparator<? super T> cmp) {
        if (lo >= hi) {
            return;                                  // 0 or 1 element: already sorted
        }
        int mid = lo + (hi - lo) / 2;                // (lo + hi) / 2 could overflow int
        sort(a, scratch, lo, mid, cmp);
        sort(a, scratch, mid + 1, hi, cmp);
        merge(a, scratch, lo, mid, hi, cmp);
    }

    /**
     * Merges the two already-sorted runs a[lo..mid] and a[mid+1..hi] back into a[lo..hi].
     * O(hi - lo + 1): each element is copied once and compared at most once per step.
     */
    private static <T> void merge(List<T> a, List<T> scratch, int lo, int mid, int hi, Comparator<? super T> cmp) {
        for (int k = lo; k <= hi; k++) {
            scratch.set(k, a.get(k));                // snapshot both runs
        }

        int left = lo;                               // next unused element of the left run
        int right = mid + 1;                         // next unused element of the right run
        for (int k = lo; k <= hi; k++) {
            if (left > mid) {                        // left run used up -> take from right
                a.set(k, scratch.get(right++));
            } else if (right > hi) {                 // right run used up -> take from left
                a.set(k, scratch.get(left++));
            } else if (cmp.compare(scratch.get(right), scratch.get(left)) < 0) {
                a.set(k, scratch.get(right++));      // right is STRICTLY smaller -> take it
            } else {
                a.set(k, scratch.get(left++));       // smaller or equal -> take left (this is what keeps it stable)
            }
        }
    }
}
