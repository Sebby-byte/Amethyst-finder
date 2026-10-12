# Amethyst Finder 1.1.0 (Fabric, Minecraft 26.3, client-side)

Outlines amethyst buds and clusters near you.

- **G** toggles it. **J** opens settings (both rebindable under Options > Controls > Amethyst Finder). Starts OFF each launch.
- **Detect from:** pick the minimum growth stage. Everything at that stage or larger is highlighted.
  Small bud, Medium bud, Large bud, or Cluster (fully grown, the default).
- **Range:** 4 to 64 blocks (default 24).
- Outline colors: small = light blue, medium = purple, large = pink, cluster = red.
- **See through blocks** (ON by default, toggle in settings): outlines show through solid blocks. Turn it OFF to only outline what you can actually see.
- Only blocks your client has already loaded are checked.
- Settings are saved in `config/amethystfinder.properties`.
- Some servers do not allow block highlighters. Check the rules before using it online.

Build: Java 25, then `gradle build` (Gradle 9.6.0). Jar: `build/libs/amethystfinder-1.1.0.jar`.
Needs Fabric Loader 0.19.5+ and Fabric API 0.161.0+26.3.
