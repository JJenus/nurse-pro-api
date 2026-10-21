package com.surf.nursepro.nurse_pro_api.service;

import com.surf.nursepro.nurse_pro_api.entity.Shift;
import com.surf.nursepro.nurse_pro_api.exception.ConflictException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Works out who ends up on which shift after a swap, without touching any entity. Pure and side-effect free
 * so the same plan can be checked when a request is created and again when it is approved.
 */
@Component
public class SwapPlanner {

    /**
     * @param a           the shift the requester gives up
     * @param b           the shift the target hands back (null for a one-way hand-off)
     * @return new assigned-nurse sets keyed by shift id
     * @throws IllegalArgumentException when the request itself is inconsistent (wrong owner)
     * @throws ConflictException        when the current assignments make the swap impossible
     */
    public Map<String, Set<String>> plan(Shift a, Shift b, String requesterId, String targetId) {
        if (requesterId.equals(targetId)) {
            throw new IllegalArgumentException("A nurse cannot swap a shift with themselves");
        }
        if (!a.getAssignedNurses().contains(requesterId)) {
            throw new IllegalArgumentException("The requester is not assigned to the shift being swapped");
        }
        if (a.getAssignedNurses().contains(targetId)) {
            throw new ConflictException("The target nurse is already assigned to this shift");
        }

        Map<String, Set<String>> result = new LinkedHashMap<>();
        Set<String> newA = new LinkedHashSet<>(a.getAssignedNurses());
        newA.remove(requesterId);
        newA.add(targetId);
        result.put(a.getId(), newA);

        if (b != null) {
            if (b.getId().equals(a.getId())) {
                throw new IllegalArgumentException("A shift cannot be swapped with itself");
            }
            if (!b.getAssignedNurses().contains(targetId)) {
                throw new IllegalArgumentException("The target nurse is not assigned to the offered shift");
            }
            if (b.getAssignedNurses().contains(requesterId)) {
                throw new ConflictException("The requester is already assigned to the offered shift");
            }
            Set<String> newB = new LinkedHashSet<>(b.getAssignedNurses());
            newB.remove(targetId);
            newB.add(requesterId);
            result.put(b.getId(), newB);
        }
        return result;
    }
}
