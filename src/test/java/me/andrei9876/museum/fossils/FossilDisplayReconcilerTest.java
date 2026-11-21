package me.andrei9876.museum.fossils;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FossilDisplayReconcilerTest {
    private static final class World {
        final List<String> tagged = new ArrayList<>();
        final List<String> legacy = new ArrayList<>();
        final List<String> removed = new ArrayList<>();
        int spawns, applies;
        boolean renderFailure;
        void reconcile(boolean donated) {
            FossilDisplayReconciler.reconcile(tagged, legacy, donated, () -> "entity-" + ++spawns,
                    entity -> { if (!tagged.contains(entity)) tagged.add(entity); legacy.remove(entity); },
                    entity -> { applies++; if (renderFailure) throw new IllegalStateException("Model unavailable"); },
                    entity -> { removed.add(entity); tagged.remove(entity); legacy.remove(entity); });
        }
    }

    @Test void firstDonationSpawnsAndEverySubsequentPassReusesEntity() {
        World world = new World();
        world.reconcile(false);
        assertEquals(0, world.spawns);
        world.reconcile(true);
        for (int i = 0; i < 10; i++) world.reconcile(true); // donation, startup, chunk/model reload, retries
        assertEquals(1, world.spawns);
        assertEquals(List.of("entity-1"), world.tagged);
        assertEquals(11, world.applies);
        world.reconcile(false);
        world.reconcile(false);
        assertEquals(List.of("entity-1"), world.removed);
    }

    @Test void failureRetainsTrackedEntityForRecovery() {
        World world = new World();
        world.renderFailure = true;
        assertThrows(IllegalStateException.class, () -> world.reconcile(true));
        assertEquals(List.of("entity-1"), world.tagged);
        world.renderFailure = false;
        world.reconcile(true);
        assertEquals(1, world.spawns);
        assertEquals(2, world.applies);
    }

    @Test void adoptsOnlyUnambiguousLegacyAndRemovesOnlyTrackedDuplicates() {
        World world = new World();
        world.legacy.add("old");
        world.reconcile(true);
        assertEquals(0, world.spawns);
        assertEquals(List.of("old"), world.tagged);
        world.tagged.add("duplicate");
        world.reconcile(true);
        assertEquals(List.of("duplicate"), world.removed);
        world.legacy.add("ambiguous");
        assertThrows(IllegalStateException.class, () -> world.reconcile(true));
        assertEquals(List.of("old"), world.tagged);
        assertEquals(List.of("ambiguous"), world.legacy);
    }

    @Test void refusesMultipleUntaggedCandidatesWithoutMutation() {
        World world = new World();
        world.legacy.addAll(List.of("a", "b"));
        assertThrows(IllegalStateException.class, () -> world.reconcile(true));
        assertEquals(0, world.spawns);
        assertTrue(world.removed.isEmpty());
    }
}
