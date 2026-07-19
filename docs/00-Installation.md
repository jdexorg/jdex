# Installation

## Requirements

- **JDK 21 or newer** — jdex is built and run against Java 21.
- **CMake** — required to build the bundled native components.
- **Git** — used to fetch the native submodules.

## Building

jdex is built from source with the included Gradle wrapper:

```sh
git submodule update --init
./gradlew shadowJar
```

This produces a self-contained jar at `app/build/libs/jdex.jar`.

## Running

Launch jdex with the script for your operating system. Each one locates a JDK, applies the options from `jvmopt.txt`, and starts the application:

```sh
./jdex_linux.sh     # Linux
./jdex_macos.sh     # macOS
jdex_windows.bat    # Windows
```

## JVM options and memory

JVM options live in `jvmopt.txt`, one per line; blank lines and lines starting with `#` are ignored.