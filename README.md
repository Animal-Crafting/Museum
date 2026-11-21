# Museum

Paper plugin for per-player museums (schematics, creatures, doors, PlaceholderAPI).

## Requirements

- Paper 1.21.4+
- Java 21+
- FastAsyncWorldEdit / WorldEdit
- PlaceholderAPI (optional)

## Build

```bash
mvn -B package
```

JAR output: `target/museum-*.jar`

## Releases

Every merge to `main` auto-increments the patch version (`v1.0.0`, `v1.0.1`, …), builds with that version, and publishes a GitHub Release with the JAR.

Manual release:

```bash
# GitHub Actions → Release → Run workflow
```

## PlaceholderAPI

Identifier: `museum`

- `%museum_donations%` / `%museum_donation_count%`
- `%museum_list:<category>%`
- `%museum_has_in:<category>:<value>%`
- `%museum_has_in:<category>:<fossilId>:<part>%`
- `%museum_<value>%`
