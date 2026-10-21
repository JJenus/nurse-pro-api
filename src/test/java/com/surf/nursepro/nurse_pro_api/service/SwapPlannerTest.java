package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SwapPlannerTest {

    private final SwapPlanner planner = new SwapPlanner();

    private Shift shift(String id, String... nurseIds) {
        Shift s = new Shift();
        s.setId(id);
        s.setAssignedNurses(new LinkedHashSet<>(Arrays.asList(nurseIds)));
        return s;
    }

    @Test
    void handOffReplacesTheRequesterWithTheTarget() {
        Shift a = shift("a", "alice", "carol");

        Map<String, Set<String>> plan = planner.plan(a, null, "alice", "bob");

        assertEquals(Set.of("bob", "carol"), plan.get("a"));
        assertEquals(1, plan.size());
    }

    @Test
    void exchangeSwapsBothShifts() {
        Shift a = shift("a", "alice");
        Shift b = shift("b", "bob");

        Map<String, Set<String>> plan = planner.plan(a, b, "alice", "bob");

        assertEquals(Set.of("bob"), plan.get("a"));
        assertEquals(Set.of("alice"), plan.get("b"));
    }

    @Test
    void planDoesNotModifyTheShifts() {
        Shift a = shift("a", "alice");
        planner.plan(a, null, "alice", "bob");
        assertEquals(Set.of("alice"), a.getAssignedNurses());
    }

    @Test
    void requesterMustOwnTheShift() {
        Shift a = shift("a", "carol");
        assertThrows(IllegalArgumentException.class, () -> planner.plan(a, null, "alice", "bob"));
    }

    @Test
    void cannotSwapWithYourself() {
        Shift a = shift("a", "alice");
        assertThrows(IllegalArgumentException.class, () -> planner.plan(a, null, "alice", "alice"));
    }

    @Test
    void targetAlreadyOnTheShiftIsAConflict() {
        Shift a = shift("a", "alice", "bob");
        assertThrows(ConflictException.class, () -> planner.plan(a, null, "alice", "bob"));
    }

    @Test
    void offeredShiftMustBelongToTheTarget() {
        Shift a = shift("a", "alice");
        Shift b = shift("b", "carol");
        assertThrows(IllegalArgumentException.class, () -> planner.plan(a, b, "alice", "bob"));
    }

    @Test
    void requesterAlreadyOnTheOfferedShiftIsAConflict() {
        Shift a = shift("a", "alice");
        Shift b = shift("b", "bob", "alice");
        assertThrows(ConflictException.class, () -> planner.plan(a, b, "alice", "bob"));
    }

    @Test
    void shiftCannotBeSwappedWithItself() {
        Shift a = shift("a", "alice");
        assertThrows(IllegalArgumentException.class, () -> planner.plan(a, a, "alice", "bob"));
    }
}
