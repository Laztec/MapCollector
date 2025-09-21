# Map Collector

Generates images of maps and stores hashes for duplicate-detection. Intended to aid in collecting map art on multiplayer servers.

The generated images and metadata can be found in `.minecraft/map-collector/`.

## Commands

| Command                                         | Description                                                                                                |
|-------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| `/map save` `[name]`                            | Saves an image and metadata of the current held map. If no name is specified, it will use the item's name. |
| `/map check`                                    | Checks if the current held map exists in your saved collection. (Visual match)                             |
| `/map imageSize` `<128\|256\|512\|1024>`        | Configures the image size to generate. Default is `128`px.                                                 |
| `/map duplicateBehaviour` `<allow\|warn\|deny>` | Configures the behaviour when attempting to save a duplicate map. Default is `warn`.                       |
| `/map confirm`                                  | Confirms the save attempt when using `defaultBehaviour warn`                                               |

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download Map Collector from [GitHub](https://github.com/Laztec/MapCollector/releases) or [Modrinth](https://modrinth.com/mod/mapcollector).
3. Place the `.jar` file into the Minecraft `mods` folder.
4. Launch Minecraft with Fabric Loader.

## Compiling

Clone the repository to a local directory:

```bash
git clone https://github.com/Laztec/MapCollector.git
```

Compile using [Gradle](https://gradle.org/) in the project's root directory:

```bash
gradlew build
```

The jar file will be located in `./build/libs/`
