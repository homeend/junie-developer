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

Releases of `JetBrains/junie` are public, so no account is strictly needed. But
GitHub allows only 60 anonymous requests an hour per network address, and a
shared office or VPN address is often already over that limit, which shows up
as an error 403. So a login is recommended. The plugin uses the first of:

1. a token entered with the **GitHub Token…** button (stored in the IDE
   password safe; no scopes needed),
2. the `GH_TOKEN` or `GITHUB_TOKEN` environment variable,
3. the GitHub CLI login, when `gh` is installed and `gh auth login` was done.

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
