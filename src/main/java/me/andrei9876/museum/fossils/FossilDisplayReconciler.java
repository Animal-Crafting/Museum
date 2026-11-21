package me.andrei9876.museum.fossils;

import java.util.*;
import java.util.function.*;

/** Stateless reconciliation makes repeated startup/load/retry passes idempotent. */
public final class FossilDisplayReconciler {
    private FossilDisplayReconciler() {}

    public static <E> void reconcile(List<E> tagged, List<E> legacy, boolean donated, Supplier<E> spawn,
                                     Consumer<E> tag, Consumer<E> apply, Consumer<E> remove) {
        if (legacy.size() > 1 || (!legacy.isEmpty() && !tagged.isEmpty()))
            throw new IllegalStateException("Ambiguous fossil entities; manual repair required");
        List<E> candidates = new ArrayList<>(tagged);
        if (candidates.isEmpty()) candidates.addAll(legacy);
        if (!donated) {
            candidates.forEach(remove);
            return;
        }
        E entity = candidates.isEmpty() ? spawn.get() : candidates.getFirst();
        // Tag before applying: retries reuse the entity even if model attachment/validation fails.
        tag.accept(entity);
        for (int i = 1; i < candidates.size(); i++) remove.accept(candidates.get(i));
        apply.accept(entity);
    }
}
