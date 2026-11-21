package me.andrei9876.museum.fossils;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FossilProgressTest {
    @Test void everyCombinationAndDonationOrder() {
        for (int mask = 0; mask < 8; mask++) {
            List<String> tokens = new ArrayList<>();
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0) {
                expected.add(FossilProgress.PARTS.get(i));
                tokens.add("Trex@" + FossilProgress.PARTS.get(i));
            }
            assertEquals(expected, FossilProgress.parts(tokens, "Trex"));
            assertEquals(mask == 7, FossilProgress.completed(tokens, "Trex"));
            assertEquals(Set.of(), FossilProgress.parts(tokens, "Mammoth"));
        }
        for (String first : FossilProgress.PARTS) for (String second : FossilProgress.PARTS) {
            if (first.equals(second)) continue;
            String third = FossilProgress.PARTS.stream().filter(p -> !p.equals(first) && !p.equals(second)).findFirst().orElseThrow();
            List<String> tokens = new ArrayList<>();
            for (String part : List.of(first, second, third)) {
                assertFalse(FossilProgress.parts(tokens, "Trex").contains(part));
                tokens.add("Trex@" + part);
                assertTrue(FossilProgress.parts(tokens, "Trex").contains(part));
            }
            assertTrue(FossilProgress.completed(tokens, "Trex"));
        }
    }

    @Test void legacyCompletionAndAliasAreReadWithoutMutation() {
        List<String> values = List.of("Pteranodon2", "Trex");
        assertEquals(Set.of("head", "body", "tail"), FossilProgress.parts(values, "Pteranodon"));
        assertTrue(FossilProgress.completed(values, "Trex"));
        assertEquals(List.of("Pteranodon2", "Trex"), values);
    }

    @Test void migrationUnionsPiecesAndIsIdempotent() {
        List<String> input = List.of("Amber", "Pteranodon2@head", "Pteranodon@head",
                "Pteranodon@body", "Pteranodon2@tail", "Mammoth@head");
        List<String> migrated = FossilProgress.migratePteranodon(input);
        assertEquals(List.of("Amber", "Pteranodon@head", "Pteranodon@body", "Pteranodon@tail", "Mammoth@head", "Pteranodon"), migrated);
        assertEquals(migrated, FossilProgress.migratePteranodon(migrated));
        assertEquals(List.of("Pteranodon"), FossilProgress.migratePteranodon(List.of("Pteranodon2")));
    }
}
