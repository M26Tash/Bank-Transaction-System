package com.banksystem.ds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UndoManagerTest {

    @Test
    void undoAndRedoAreLifo() {
        UndoManager<String> manager = new UndoManager<>();
        manager.record("a");
        manager.record("b");

        assertEquals("b", manager.commitUndo());
        assertEquals("a", manager.commitUndo());
        assertFalse(manager.canUndo());

        assertEquals("a", manager.commitRedo());
        assertEquals("b", manager.commitRedo());
        assertFalse(manager.canRedo());
    }

    @Test
    void peekDoesNotChangeState() {
        UndoManager<String> manager = new UndoManager<>();
        manager.record("a");
        assertEquals("a", manager.peekUndo().orElseThrow());
        assertTrue(manager.canUndo());
        assertFalse(manager.canRedo());
    }

    @Test
    void newOperationClearsRedo() {
        UndoManager<String> manager = new UndoManager<>();
        manager.record("a");
        manager.commitUndo();
        assertTrue(manager.canRedo());
        manager.record("b");
        assertFalse(manager.canRedo());
    }

    @Test
    void redoCanPushAReplacement() {
        UndoManager<String> manager = new UndoManager<>();
        manager.record("original");
        manager.commitUndo();
        assertEquals("copy", manager.commitRedo("copy"));
        assertEquals("copy", manager.peekUndo().orElseThrow());
        assertFalse(manager.canRedo());
    }

    @Test
    void emptyStacksThrow() {
        UndoManager<String> manager = new UndoManager<>();
        assertThrows(IllegalStateException.class, manager::commitUndo);
        assertThrows(IllegalStateException.class, manager::commitRedo);
    }
}
