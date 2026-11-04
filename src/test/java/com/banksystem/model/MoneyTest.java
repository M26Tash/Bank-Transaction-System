package com.banksystem.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesValidAmounts() {
        assertEquals(1200L, Money.parse("12"));
        assertEquals(1250L, Money.parse("12.5"));
        assertEquals(1250L, Money.parse(" 12,50 "));
        assertEquals(123L, Money.parse("1.230"));
        assertEquals(1L, Money.parse("0.01"));
    }

    @Test
    void rejectsInvalidAmounts() {
        for (String bad : new String[] {"", "  ", "abc", "0", "-5", "1.234", "1e3x", "99999999999"}) {
            assertThrows(IllegalArgumentException.class, () -> Money.parse(bad), bad);
        }
    }

    @Test
    void formatsCents() {
        assertEquals("12.50 USD", Money.format(1250));
        assertEquals("0.05 USD", Money.format(5));
        assertEquals("0.00 USD", Money.format(0));
    }
}
