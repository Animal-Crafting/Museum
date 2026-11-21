# Museum

Paper plugin for per-player museums (schematics, creatures, doors, PlaceholderAPI).

## Requirements

- Paper 1.21.4+
- Java 17+
- FastAsyncWorldEdit / WorldEdit
- PlaceholderAPI (optional)

## Build

```bash
mvn -B package
```

JAR output: `target/museum-*.jar`

## Releases

Push a version tag to publish a GitHub Release with the built JAR:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## PlaceholderAPI

Identifier: `museum`

- `%museum_donations%` / `%museum_donation_count%`
- `%museum_list:<category>%`
- `%museum_has_in:<category>:<value>%`
- `%museum_has_in:<category>:<fossilId>:<part>%`
- `%museum_<value>%`
