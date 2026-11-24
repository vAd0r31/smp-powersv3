# SMP Powers (Fabric 26.2, server-side only)

Players install NOTHING. Put the jar + Fabric API in the server `mods` folder.

## Build
1. Install Java 25 and Gradle (or use the Fabric template wrapper).
2. In this folder run: `gradle build`  (or `./gradlew build` if you copied a wrapper)
3. Jar is in `build/libs/` (use the one WITHOUT "-sources").

## Install (Apex)
Upload the jar and Fabric API to the `mods` folder, restart.

## Commands
Players: /ability  /ultimate  /withdraw  /power info
Admins: /power set|clear|charge|level|essence|reload
Bind /ability to a key with the Command Keys mod (optional, client side).

## Config
config/smppowers/config.json (all numbers), then /power reload.

## If the build fails after a version change
Almost every Minecraft call that can break is in Compat.java.
