# What JunieDeveloper relies on inside Air

Read from the installed Air plugin `com.intellij.air` 263.7307.291 (class files,
`javap`), not from source. Re-check after an Air update if something stops working.

## Switching the Junie command

- Registry keys `air.junie.command.windows` / `air.junie.command.unix`
  (declared in Air's `plugin.xml`, `restartRequired="false"`).
- Read in `com.intellij.air.backend.junie.common.JunieCliSupport.commandOverride()`,
  which is called when a command is built, so a change should apply to the next
  agent that starts, with no IDE restart. This is inferred from the bytecode and
  the key's `restartRequired="false"`; it has not been observed in a running Air yet.
- The same key as a **system property** (`-Dair.junie.command.windows=…`) is read
  first and wins over the Registry. JunieDeveloper warns when one is set.
- The value is an executable path. Air's own description of the key uses a
  `.cmd` file as its Windows example, which is why JAR launchers are `.cmd` there.
- Air sets `JBR_BIN_DIR` for the launcher and appends `--acp=true`.

## Resetting the agent pool

- The pool is the application service `com.intellij.air.acp.pool.AcpAgentClientPool`
  (module `intellij.air.backend.acp`).
- The "Reset Pool" button (Debug ACP Chat > Agent Pool) goes
  `AcpDebugAgentPoolPanel.resetPool` → RPC `AcpDebugBackendApi.resetPool` →
  `AcpDebugBackendApiImpl.resetPool()` →
  `AcpAgentClientPoolDebugKt.evictAcpAgentClientPool(agentId = null)` →
  `AcpAgentClientPool.evictAll()`.
- `evictAcpAgentClientPool(String?, Continuation)` is a public static suspend
  function. With a non-null id it calls `evictAgent(id)` instead; JunieDeveloper
  passes null, exactly like the button, so **all** pooled agents are evicted,
  not only Junie.
- Only the *action* that opens the debug window is `internal="true"`; the function
  works without `idea.is.internal`.
- Air's modules are `visibility="internal"`, so no compile-time dependency is
  possible. `AirPoolResetter` gets a class loader from Air's
  `AcpDebugHttpHandler` (registered on the `com.intellij.httpRequestHandler`
  extension point; its module depends on the pool module) and calls the function
  by reflection.
- Not used, but available: Air has a dev HTTP API under `/api/air/debug` on the
  IDE's built-in server (off unless `air.acp.debug.server.enabled` is true) with
  a pool-evict route.

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
