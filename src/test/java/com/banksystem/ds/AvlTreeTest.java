package com.banksystem.ds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class AvlTreeTest {

    @Test
    void emptyTree() {
        AvlTree<Integer, String> tree = new AvlTree<>();
        assertTrue(tree.isEmpty());
        assertEquals(0, tree.height());
        assertNull(tree.get(1));
        assertNull(tree.remove(1));
        assertTrue(tree.values().isEmpty());
    }

    @Test
    void sortedInsertsStayBalanced() {
        AvlTree<Integer, Integer> tree = new AvlTree<>();
        int n = 10_000;
        for (int i = 0; i < n; i++) {
            tree.put(i, i);
        }
        assertEquals(n, tree.size());
        assertTrue(tree.isValid());
        double maxAvlHeight = 1.45 * (Math.log(n + 2) / Math.log(2));
        assertTrue(tree.height() <= maxAvlHeight, "height " + tree.height() + " exceeds " + maxAvlHeight);
    }

    @Test
    void putReplacesValueForEqualKey() {
        AvlTree<String, String> tree = new AvlTree<>();
        tree.put("a", "first");
        tree.put("a", "second");
        assertEquals(1, tree.size());
        assertEquals("second", tree.get("a"));
    }

    @Test
    void valuesAreInKeyOrder() {
        AvlTree<Integer, String> tree = new AvlTree<>();
        for (int k : new int[] { 5, 3, 8, 1, 4, 7, 9, 2, 6 }) {
            tree.put(k, "v" + k);
        }
        assertEquals(List.of("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9"), tree.values());
    }

    @Test
    void rangeIsHalfOpen() {
        AvlTree<Integer, Integer> tree = new AvlTree<>();
        for (int i = 0; i < 20; i++) {
            tree.put(i, i);
        }
        assertEquals(List.of(5, 6, 7, 8, 9), tree.range(5, 10));
        assertEquals(List.of(), tree.range(7, 7));
        assertEquals(List.of(0, 1), tree.range(-100, 2));
        assertEquals(List.of(18, 19), tree.range(18, 100));
    }

    @Test
    void removeKeepsTreeValid() {
        AvlTree<Integer, Integer> tree = new AvlTree<>();
        for (int i = 1; i <= 15; i++) {
            tree.put(i, i);
        }
        assertEquals(Integer.valueOf(8), tree.remove(8));
        assertEquals(Integer.valueOf(1), tree.remove(1));
        assertFalse(tree.containsKey(8));
        assertNull(tree.remove(8));
        assertEquals(13, tree.size());
        assertTrue(tree.isValid());
    }

    @Test
    void randomOperationsMatchTreeMap() {
        Random random = new Random(42);
        AvlTree<Integer, Integer> tree = new AvlTree<>();
        TreeMap<Integer, Integer> reference = new TreeMap<>();

        for (int step = 0; step < 5_000; step++) {
            int key = random.nextInt(500);
            if (random.nextInt(3) == 0) {
                assertEquals(reference.remove(key), tree.remove(key));
            } else {
                tree.put(key, step);
                reference.put(key, step);
            }
            if (step % 100 == 0) {
                assertTrue(tree.isValid(), "invariants broken at step " + step);
            }
        }

        assertTrue(tree.isValid());
        assertEquals(reference.size(), tree.size());
        assertEquals(new ArrayList<>(reference.values()), tree.values());
        assertEquals(new ArrayList<>(reference.subMap(100, 200).values()), tree.range(100, 200));
    }
}
