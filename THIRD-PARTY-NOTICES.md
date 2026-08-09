# Third-Party Notices

Kaleidoscope Tavern embeds Sparrow YAML for its operator-owned configuration
files. It also builds against third-party server APIs whose classes are not
bundled in the release JAR; server operators must install those runtime
dependencies separately. The build audits both boundaries.

## Bundled runtime library

### Sparrow YAML

- Module used: `sparrow-yaml`
- Version used by this project: 1.0.7
- Project: https://github.com/Xiao-MoMi/sparrow-yaml
- License: GNU General Public License v3.0 (GPL-3.0)
- License text: `META-INF/third-party-licenses/SPARROW-YAML-GPL-3.0.txt`
- Distribution: bundled in the plugin JAR to parse barrel and shaker recipe YAML

### SnakeYAML Engine (relocated by Sparrow YAML)

- Module used: `snakeyaml-engine`
- Version embedded upstream: 3.1-SNAPSHOT-forked
- Project: https://bitbucket.org/snakeyaml/snakeyaml-engine/
- License: Apache License 2.0
- License text: `META-INF/third-party-licenses/SNAKEYAML-APACHE-2.0.txt`
- Distribution: bundled inside the Sparrow YAML artifact under its relocated package

## Required runtime and compile-only dependencies

### CraftEngine

- Modules used: `craft-engine-core`, `craft-engine-bukkit`, and
  `craft-engine-bukkit-proxy`
- Versions used by this project: 26.7.4 / proxy 26.7
- Project: https://github.com/Xiao-MoMi/craft-engine
- License: GNU General Public License v3.0 (GPL-3.0)
- Distribution: not bundled; installed separately on the server

### CustomCrops

- Module used: `custom-crops`
- Version used by this project: 3.6.52
- Project: https://github.com/Xiao-MoMi/Custom-Crops
- License: GNU General Public License v3.0 (GPL-3.0)
- Distribution: not bundled; installed separately on the server

### Paper API

- Module used: `paper-api`
- Version used by this project: 26.2 build 65 beta
- Project: https://github.com/PaperMC/Paper
- License: see the upstream repository and individual source notices
- Distribution: not bundled; supplied by the Paper server

## Optional compile-only integration

### PlaceholderAPI

- Module used: `placeholderapi`
- Version used by this project: 2.11.6
- Project: https://github.com/PlaceholderAPI/PlaceholderAPI
- License: GNU General Public License v3.0 (GPL-3.0)
- Distribution: not bundled; installed separately when its integration is used

## Design reference only

### CustomFishing

- Feature studied: bitmap-font bars, pointer layering, and offset glyphs used by
  its accurate-click fishing games
- Project: https://github.com/Xiao-MoMi/Custom-Fishing
- License: GNU General Public License v3.0 (GPL-3.0)
- Distribution: neither its classes nor its resources are bundled; the shaker
  HUD is an independent implementation using Kaleidoscope Tavern's archived
  artwork, timing, and outcome rules

## Build and test tools

Gradle, the Foojay toolchain resolver, JUnit, and their transitive dependencies
are used only to build or test the project and are not bundled in the plugin
JAR. The tracked Gradle Wrapper JAR contains its own `META-INF/LICENSE` notice.

This notice is informational and does not replace any upstream license text.
The Kaleidoscope Tavern source-code and artistic-resource licenses are provided
separately as `LICENSE-CODE` and `LICENSE-ASSETS`.
