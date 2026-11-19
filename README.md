# JunieDeveloper

IntelliJ plugin that switches which Junie build **Air** runs, for debugging Junie.

- **Download Junie Release…** searches the GitHub releases of `JetBrains/junie`
  (channel filter, paging, exact-tag lookup with Enter) and downloads the build
  for this machine's OS and CPU.
- **Add Local JAR…** adds a locally built Junie JAR. The JAR is used where it
  lies, so rebuilding it is enough; no need to add it again.
- **Use Selected Junie** (or double-click a row) points Air at that Junie and
  resets Air's agent pool, so the next message starts a fresh agent.
  The first row, *Default (ACP registry)*, switches back to Air's own Junie.
- **Reset Agent Pool** does only the reset, e.g. after rebuilding a JAR.

Tool window: **Junie Developer** (bottom). Needs an IDE with Air (2026.3+).

## Build

    ./test.sh                      # unit tests
    ./dist.sh [deploy-dir]         # plugin zip in build/distributions

Install the zip via Settings > Plugins > gear > Install Plugin from Disk.

## Where things are kept

`~/.junie-developer/` (shared by every IDE that has the plugin):

    library.json    the list
    versions/       unpacked downloaded releases
    launchers/      generated launcher scripts for local JARs

The optional GitHub token is stored in the IDE password safe.

## How it works

See [docs/air-internals.md](docs/air-internals.md).
