package me.andrei9876.museum.fossils;

import me.andrei9876.museum.models.MuseumRegion;
import java.util.*;

/** Donation transitions only. Persistence and rendering are deliberately separate operations. */
public final class FossilDonations {
    private FossilDonations() {}

    public static boolean donate(MuseumRegion museum, String fossil, String part) {
        if (part == null || !FossilProgress.PARTS.contains(part)
                || FossilProgress.parts(museum.getValue("Fossils"), fossil).contains(part)) return false;
        museum.addValue("Fossils", fossil + "@" + part);
        if (FossilProgress.completed(museum.getValue("Fossils"), fossil)) museum.addValue("Fossils", fossil);
        return true;
    }

    public static boolean remove(MuseumRegion museum, String fossil, String part) {
        Set<String> donated = FossilProgress.parts(museum.getValue("Fossils"), fossil);
        if (donated.isEmpty() || (part != null && !donated.contains(part))) return false;
        List<String> after = new ArrayList<>(museum.getValue("Fossils"));
        for (String id : fossil.equals("Pteranodon") ? List.of(fossil, "Pteranodon2") : List.of(fossil)) {
            after.remove(id);
            for (String piece : FossilProgress.PARTS) after.remove(id + "@" + piece);
        }
        if (part != null) {
            donated.remove(part);
            for (String piece : donated) after.add(fossil + "@" + piece);
        }
        museum.replaceValues("Fossils", after);
        return true;
    }
}
