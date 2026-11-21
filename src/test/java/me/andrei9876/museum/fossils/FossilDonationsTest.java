package me.andrei9876.museum.fossils;

import com.sk89q.worldedit.math.BlockVector3;
import me.andrei9876.museum.models.MuseumRegion;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FossilDonationsTest {
    private MuseumRegion museum() {
        return new MuseumRegion(UUID.randomUUID(), "Museums", BlockVector3.ZERO, BlockVector3.ZERO,
                BlockVector3.ZERO, new Vector(), Map.of());
    }

    @Test void allSixOrdersRejectDuplicatesAndRetainExistingCountSemantics() {
        for (String first : FossilProgress.PARTS) for (String second : FossilProgress.PARTS) {
            if (first.equals(second)) continue;
            MuseumRegion museum = museum();
            String third = FossilProgress.PARTS.stream().filter(p -> !p.equals(first) && !p.equals(second)).findFirst().orElseThrow();
            assertTrue(FossilDonations.donate(museum, "Trex", first));
            assertEquals(1, museum.getDonationCount());
            assertFalse(FossilDonations.donate(museum, "Trex", first));
            assertTrue(FossilDonations.donate(museum, "Trex", second));
            assertEquals(2, museum.getDonationCount());
            assertTrue(FossilDonations.donate(museum, "Trex", third));
            assertEquals(1, museum.getDonationCount()); // preserve existing completion count behavior
            assertTrue(museum.getValue("Fossils").contains("Trex"));
            for (String part : FossilProgress.PARTS) assertFalse(FossilDonations.donate(museum, "Trex", part));
        }
    }

    @Test void removingFromLegacyCompletionMaterializesRemainingPiecesOnly() {
        MuseumRegion museum = museum();
        museum.addValue("Bugs", "Bug");
        museum.addValue("Fossils", "Trex");
        museum.addValue("Fossils", "Amber");
        museum.addValue("Fish", "Fish");
        assertTrue(FossilDonations.remove(museum, "Trex", "body"));
        assertEquals(List.of("Amber", "Trex@head", "Trex@tail"), museum.getValue("Fossils"));
        assertEquals(List.of("Bugs", "Fossils", "Fish"), new ArrayList<>(museum.getCategories()));
        assertFalse(FossilDonations.remove(museum, "Trex", "body"));
        assertTrue(FossilDonations.donate(museum, "Trex", "body"));
        assertTrue(FossilDonations.remove(museum, "Trex", null));
        assertEquals(List.of("Amber"), museum.getValue("Fossils"));
        assertEquals(List.of("Bug"), museum.getValue("Bugs"));
        assertEquals(List.of("Fish"), museum.getValue("Fish"));
    }

    @Test void removalAndDonationRespectAliasProgressAndRejectBadParts() {
        MuseumRegion museum = museum();
        museum.addValue("Fossils", "Pteranodon2@head");
        assertFalse(FossilDonations.donate(museum, "Pteranodon", "head"));
        assertFalse(FossilDonations.donate(museum, "Pteranodon", "unknown"));
        assertTrue(FossilDonations.donate(museum, "Pteranodon", "body"));
        assertTrue(FossilDonations.remove(museum, "Pteranodon", "head"));
        assertEquals(List.of("Pteranodon@body"), museum.getValue("Fossils"));
        assertTrue(FossilDonations.remove(museum, "Pteranodon", null));
        assertNull(museum.getValue("Fossils"));
    }
}
