# Third-Party Notices

Kaleidoscope Tavern builds against third-party APIs but does not bundle their
plugin or server classes in the release JAR. Server operators must obtain and
install runtime dependencies separately and comply with each upstream license.
The build's JAR audit rejects embedded classes from these APIs.

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

## Build and test tools

Gradle, the Foojay toolchain resolver, JUnit, and their transitive dependencies
are used only to build or test the project and are not bundled in the plugin
JAR. The tracked Gradle Wrapper JAR contains its own `META-INF/LICENSE` notice.

This notice is informational and does not replace any upstream license text.
The Kaleidoscope Tavern source-code and artistic-resource licenses are provided
separately as `LICENSE-CODE` and `LICENSE-ASSETS`.
