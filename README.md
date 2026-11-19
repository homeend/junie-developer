# JunieDeveloper

IntelliJ plugin that switches which Junie build **Air** runs, for debugging Junie.

- **Download Junie Release…** searches the GitHub releases of `JetBrains/junie`
  (channel filter, paging, exact-tag lookup with Enter) and downloads the build
  for this machine's OS and CPU.
- **Add Local JAR…** adds a locally built Junie JAR. The JAR is used where it
  lies, so rebuilding it is enough; no need to add it again.
- **Use Selected Junie** (or double-click a row) points Air at that Junie and
  stops Air's running Junie agents (other agents keep running), so the next
  message starts a fresh one. Open Junie chats lose their process too; avoid
  switching while a turn is running.
  The first row, *Default (ACP registry)*, switches back to Air's own Junie.
- **Reset Agent Pool** does only the reset, e.g. after rebuilding a JAR.

Tool window: **Junie Developer** (bottom). Needs an IDE with Air (2026.2 or newer).

The switch applies to projects that run locally. A project in WSL, Docker, SSH
or remote development keeps using Air's own Junie.

## GitHub access

The plugin does not need the `gh` command-line tool or a GitHub account. It reads
the public releases of `JetBrains/junie` over HTTPS directly.

Without a token, GitHub allows 60 requests an hour per network address, which is
enough for normal use. If you hit that limit, create a personal access token (no
scopes needed for public releases) and enter it with the **GitHub Token…** button
in the tool window. It is stored in the IDE password safe.

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
