# jdex
Apk disassembler and decompiler based on [jadx](https://github.com/skylot/jadx)

![Bytecode](public/jdex.png)
![Native](public/jdex_n.png)

# Features
- Most of jadx's features
- Bytecode-first disassembler
- Emulation / code reconstruction
  > ⚠️ **Warning:** JDec (jdex's emulator-based decompiler) reconstructs code through emulation, so its output may be inaccurate. Always verify against jadx's Java (Code) view.

# Building
* Requires Java 21
* Requires CMake

```sh
git submodule update --init
./gradlew shadowJar
```
```sh
java -jar app/build/libs/jdex.jar
```

# WIP 

Project is in early stages, some features are either missing on partially implemented.
