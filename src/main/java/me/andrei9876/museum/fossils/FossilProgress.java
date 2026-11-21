package me.andrei9876.museum.fossils;

import java.util.*;

/** Existing donation tokens remain authoritative; rendering never writes progress. */
public final class FossilProgress {
    public static final List<String> PARTS = List.of("head", "body", "tail");
    private FossilProgress() {}

    public static Set<String> parts(Collection<String> values, String fossil) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String id : fossil.equals("Pteranodon") ? List.of(fossil, "Pteranodon2") : List.of(fossil)) {
            if (values.contains(id)) return new LinkedHashSet<>(PARTS);
            for (String part : PARTS) if (values.contains(id + "@" + part)) result.add(part);
        }
        return result;
    }

    public static boolean completed(Collection<String> values, String fossil) {
        return parts(values, fossil).size() == PARTS.size();
    }

    /** Returns a copy; callers explicitly decide when to persist the alias migration. */
    public static List<String> migratePteranodon(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            result.add(value.equals("Pteranodon2") ? "Pteranodon"
                    : value.startsWith("Pteranodon2@") ? "Pteranodon" + value.substring(11) : value);
        }
        if (completed(result, "Pteranodon")) result.add("Pteranodon");
        return new ArrayList<>(result);
    }
}
