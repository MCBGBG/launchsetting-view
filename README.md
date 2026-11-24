# Launch Settings Editor for JetBrains Rider

A Rider plugin that turns the raw `Properties/launchSettings.json` editing experience into a structured form.

## What it does

When a **.NET Launch Settings Profile** run configuration is selected in *Run/Debug Configurations*, Rider shows a hint line:

> ⚠ Profile parameters are set by `MyProject/Properties/launchSettings.json`  **open in editor**

This plugin takes over that **open in editor** link and opens a form-based editor instead of the raw file. If the file does not exist yet, a **Create launchSettings.json** button appears next to the *Launch profile* selector.

### The form

```
┌─────────────┬──────────────────────────────────────────────┐
│ + −         │ 名称:        [ http                         ] │
│             │ 命令:        [ Project                    ▼ ] │
│  http       │ 程序实参:    [                              ] │
│  https  ◀── │ 可执行文件路径: [                           ] │
│  test6      │ 工作目录:    [                              ] │
│             │ 应用程序 URL:[ http://localhost:5199         ] │
│             │ 启动 URL:    [                              ] │
│             │              ☐ 启动浏览器  ☐ 使用本机调试      │
│ 以 JSON 文本 │ 环境变量:                                     │
│ 编辑…       │ ┌──────────────────────┬──────────────────┐  │
│             │ │ ASPNETCORE_ENVIRONMENT│ Development      │  │
│             │ └──────────────────────┴──────────────────┘  │
└─────────────┴──────────────────────────────────────────────┘
```

- **Left**: profile list with +/−, mirroring the run configuration tree.
- **Right**: fields for the selected profile. Fields that are irrelevant for the chosen command
  (`Project` / `IISExpress` → no command-line args, executable path or working directory) are disabled.
- **Environment variables** are a table. The *name* column suggests common variables
  (`ASPNETCORE_ENVIRONMENT`, `DOTNET_ENVIRONMENT`, `Logging__LogLevel__Default`, …); the *value*
  column offers the matching fixed choices (`Development` / `Staging` / `Production`, log levels, …).
  Both columns stay freely typeable.
- **Lossless**: only the fields above are written back. `$schema`, `dotnetRunMessages`, `iisSettings`
  and any other custom keys are preserved.
- **"以 JSON 文本编辑…"** (Edit as JSON text) is the escape hatch for anything the form does not cover.
- Saves go through the IntelliJ VFS, so Rider notices the change immediately (profile dropdown refreshes).

## Compatibility

Built and tested against **Rider 2026.3** (`untilBuild = 263.*`). It relies on Rider's internal
run-configuration editor wiring (there is no official extension point for decorating it), so other
versions are not guaranteed.

## Development

```bash
./gradlew buildPlugin          # → build/distributions/*.zip
./gradlew runIde                # sandbox Rider
./gradlew runIde -Dlsplugin.selftest=true -Dlsplugin.testCsproj=<path/to/x.csproj> -Dlsplugin.open=<solution dir>
```

The build needs **Gradle to run on JDK 25** (the platform's requirement) and uses the locally installed
Rider as the platform dependency. Rider is looked up at `-PriderHome=<path>`, `$RIDER_HOME`,
`~/Applications/Rider.app` or `/Applications/Rider.app` (in that order); adjust if yours lives elsewhere.
Point Gradle at Rider's bundled JBR via your user-level `~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/path/to/Rider.app/Contents/jbr/Contents/Home
```

### How the injection works

Rider's run-configuration editor does not go through the platform's `RunConfigurationExtension`
mechanism, so the plugin watches AWT events via `IdeEventQueue`, locates the editor panel by its
`ConfigurationSettingsEditorWrapper.CONFIGURATION_EDITOR_KEY` data key, wraps it in a transparent
panel, and replaces the hint row's `ActionLink` with its own. See `LaunchSettingsPanel.kt` and
`RunConfigurationsDialogPatcher.kt`.

## License

MIT
