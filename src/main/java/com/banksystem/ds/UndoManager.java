package com.banksystem.ds;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public final class UndoManager<T> {

    private final Deque<T> undoStack = new ArrayDeque<>();
    private final Deque<T> redoStack = new ArrayDeque<>();

    public void record(T operation) {
        undoStack.push(operation);
        redoStack.clear();
    }

    public Optional<T> peekUndo() {
        return Optional.ofNullable(undoStack.peek());
    }

    public Optional<T> peekRedo() {
        return Optional.ofNullable(redoStack.peek());
    }

    public T commitUndo() {
        if (undoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to undo");
        }
        T operation = undoStack.pop();
        redoStack.push(operation);
        return operation;
    }

    public T commitRedo() {
        if (redoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to redo");
        }
        return commitRedo(redoStack.peek());
    }

    public T commitRedo(T redone) {
        if (redoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to redo");
        }
        redoStack.pop();
        undoStack.push(redone);
        return redone;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void clear() {
        undoStack.clear();
        redoStack.clear();
    }
}
