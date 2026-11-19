# What JunieDeveloper relies on inside Air

Two sources: the installed Air plugin `com.intellij.air` 263.7307.291 (class files,
`javap`), and a read of the Air source at revision `01a5b3679f9d` done by Junie.
Both agree. Re-check after an Air update if something stops working.

## Switching the Junie command

- Registry keys `air.junie.command.windows` / `air.junie.command.unix`
  (declared in Air's `plugin.xml`, `restartRequired="false"`).
- Read in `com.intellij.air.backend.junie.common.JunieCliSupport.commandOverride()`,
  which is called each time a new ACP start configuration is resolved, so a change
  applies to the next agent that starts, with no IDE restart. Confirmed in the
  source; not yet observed in a running Air. Existing agents keep their old
  executable, which is why a pool reset follows every switch.
- The same key as a **system property** (`-Dair.junie.command.windows=…`) is read
  first and wins over the Registry. JunieDeveloper warns when one is set.
- The value is an executable path. Air's own description of the key uses a
  `.cmd` file as its Windows example, which is why JAR launchers are `.cmd` there.
- Air sets `JBR_BIN_DIR` for the launcher and appends `--acp=true`.

## Resetting the agent pool

- The pool is the application service `com.intellij.air.acp.pool.AcpAgentClientPool`
  (module `intellij.air.backend.acp`), one per IDE process, shared by all projects.
- The "Reset Pool" button (Debug ACP Chat > Agent Pool) goes
  `AcpDebugAgentPoolPanel.resetPool` → RPC `AcpDebugBackendApi.resetPool` →
  `AcpDebugBackendApiImpl.resetPool()` →
  `evictAcpAgentClientPool(agentId = null)` → `AcpAgentClientPool.evictAll()` →
  `evictClientsMatching(DEBUG_POOL_RESET) { true }` → `AcpAgentClient.quarantine()`.
- JunieDeveloper calls `evictClientsMatching(DEBUG_POOL_RESET)` itself with the
  predicate `key.agentId.fullId == "acp.registry.junie"`, so only Junie is
  stopped. If those members are missing it falls back to
  `evictAcpAgentClientPool(null)`, which stops every agent like the button.
- **Never call `evictAcpAgentClientPool("acp.registry.junie")`.** The non-null
  branch calls `evictAgent()` → `forceStopForTest()`, a test path that drops the
  leases first, so open chats never hear `onDisconnected`.
- Quarantine also disconnects clients attached to open chats; chat history stays.
  With `DEBUG_POOL_RESET` an idle chat shows no failure row and its next prompt
  starts a new process. A turn that is running is interrupted.
- The reset returns before the old processes have exited (on Windows they are
  killed asynchronously, with a wait of about five seconds), and it does not
  refill the pool; the next acquisition does.
- Only the *action* that opens the debug window is `internal="true"`; the
  functions work without `idea.is.internal`.
- Air's modules are `visibility="internal"` and the API is `@ApiStatus.Internal`.
  `AirPoolResetter` gets a class loader from Air's `AcpDebugHttpHandler`
  (registered on the `com.intellij.httpRequestHandler` extension point; its
  module depends on the pool module) and uses reflection, resolving every member
  before calling anything.

## What can override the selection

In Air's order of precedence:

1. A Junie entry in the installed overlay,
   `~/.jetbrains/acp-registry-installed-overlay.json`.
2. The system property `air.junie.command.*`, even when blank.
3. The Registry key, which is what JunieDeveloper sets.

JunieDeveloper shows a warning for 1 and 2.

The command override applies only when the agent runs in the **local**
environment. A project in WSL, Docker, SSH or remote development uses the
registry Junie regardless. Junie must also still be installed from the ACP
registry: the override replaces the command, not the installation.

## Junie release layout

Releases of `JetBrains/junie` are packaged apps, one zip per OS/CPU:

| OS      | launcher inside the zip                          |
|---------|--------------------------------------------------|
| Windows | `junie\junie.exe` (entries use backslashes)       |
| Linux   | `junie-app/bin/junie`                            |
| macOS   | `Applications/junie.app/Contents/MacOS/junie`    |

Air's own ACP registry installs from a different repository,
`JetBrains/junie-acp-release`, with the same layout.

## What has and has not been run

- Run for real: release list parsing against the live API, unpacking of the real
  Windows and Linux zips, the unpacked Linux launcher (`--version`), and the
  generated Windows `.cmd` launcher with the snapshot JAR on the IDE's own JBR 25
  (`--version`, path with a space).
- Never run: everything that needs the IDE, i.e. the Registry switch, the
  reflective pool reset, the tool window and the download task.
