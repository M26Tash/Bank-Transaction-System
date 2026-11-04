package com.banksystem.ds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AvlTree<K extends Comparable<? super K>, V> {

    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> left;
        Node<K, V> right;
        int height = 1;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private Node<K, V> root;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int height() {
        return height(root);
    }

    public V get(K key) {
        Objects.requireNonNull(key, "key");
        Node<K, V> node = root;
        while (node != null) {
            int cmp = key.compareTo(node.key);
            if (cmp == 0) {
                return node.value;
            }
            node = cmp < 0 ? node.left : node.right;
        }
        return null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public void put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        root = put(root, key, value);
    }

    private Node<K, V> put(Node<K, V> node, K key, V value) {
        if (node == null) {
            size++;
            return new Node<>(key, value);
        }
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = put(node.left, key, value);
        } else if (cmp > 0) {
            node.right = put(node.right, key, value);
        } else {
            node.value = value;
            return node;
        }
        return rebalance(node);
    }

    public V remove(K key) {
        V existing = get(key);
        if (existing != null) {
            root = remove(root, key);
            size--;
        }
        return existing;
    }

    private Node<K, V> remove(Node<K, V> node, K key) {
        int cmp = key.compareTo(node.key);
        if (cmp < 0) {
            node.left = remove(node.left, key);
        } else if (cmp > 0) {
            node.right = remove(node.right, key);
        } else {
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }

            Node<K, V> successor = min(node.right);
            successor.right = removeMin(node.right);
            successor.left = node.left;
            node = successor;
        }
        return rebalance(node);
    }

    private Node<K, V> min(Node<K, V> node) {
        while (node.left != null) {
            node = node.left;
        }
        return node;
    }

    private Node<K, V> removeMin(Node<K, V> node) {
        if (node.left == null) {
            return node.right;
        }
        node.left = removeMin(node.left);
        return rebalance(node);
    }

    public List<V> range(K fromInclusive, K toExclusive) {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        List<V> result = new ArrayList<>();
        range(root, fromInclusive, toExclusive, result);
        return result;
    }

    private void range(Node<K, V> node, K from, K to, List<V> out) {
        if (node == null) {
            return;
        }
        int cmpFrom = from.compareTo(node.key);
        int cmpTo = to.compareTo(node.key);
        if (cmpFrom < 0) {
            range(node.left, from, to, out);
        }
        if (cmpFrom <= 0 && cmpTo > 0) {
            out.add(node.value);
        }
        if (cmpTo > 0) {
            range(node.right, from, to, out);
        }
    }

    public List<V> values() {
        List<V> result = new ArrayList<>(size);
        inOrder(root, result);
        return result;
    }

    private void inOrder(Node<K, V> node, List<V> out) {
        if (node == null) {
            return;
        }
        inOrder(node.left, out);
        out.add(node.value);
        inOrder(node.right, out);
    }

    private static int height(Node<?, ?> node) {
        return node == null ? 0 : node.height;
    }

    private static int balanceFactor(Node<?, ?> node) {
        return height(node.left) - height(node.right);
    }

    private static void updateHeight(Node<?, ?> node) {
        node.height = 1 + Math.max(height(node.left), height(node.right));
    }

    private Node<K, V> rebalance(Node<K, V> node) {
        updateHeight(node);
        int balance = balanceFactor(node);
        if (balance > 1) {
            if (balanceFactor(node.left) < 0) {
                node.left = rotateLeft(node.left);
            }
            return rotateRight(node);
        }
        if (balance < -1) {
            if (balanceFactor(node.right) > 0) {
                node.right = rotateRight(node.right);
            }
            return rotateLeft(node);
        }
        return node;
    }

    private Node<K, V> rotateRight(Node<K, V> y) {
        Node<K, V> x = y.left;
        y.left = x.right;
        x.right = y;
        updateHeight(y);
        updateHeight(x);
        return x;
    }

    private Node<K, V> rotateLeft(Node<K, V> x) {
        Node<K, V> y = x.right;
        x.right = y.left;
        y.left = x;
        updateHeight(x);
        updateHeight(y);
        return y;
    }

    boolean isValid() {
        return count(root) == size && isValid(root, null, null);
    }

    private boolean isValid(Node<K, V> node, K low, K high) {
        if (node == null) {
            return true;
        }
        if (low != null && node.key.compareTo(low) <= 0) {
            return false;
        }
        if (high != null && node.key.compareTo(high) >= 0) {
            return false;
        }
        if (node.height != 1 + Math.max(height(node.left), height(node.right))) {
            return false;
        }
        if (Math.abs(balanceFactor(node)) > 1) {
            return false;
        }
        return isValid(node.left, low, node.key) && isValid(node.right, node.key, high);
    }

    private int count(Node<K, V> node) {
        return node == null ? 0 : 1 + count(node.left) + count(node.right);
    }
}
