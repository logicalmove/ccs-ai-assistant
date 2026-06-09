# CCS AI Assistant

AI Assistant plugin for TI Code Composer Studio (CCS) 12.8.0.

Embed an AI chat assistant directly into your CCS editor with streaming responses, right-click code analysis, and keyboard shortcuts.

## Features

- **AI Chat Panel** — StyledText output + text input, real-time streaming display
- **Right-Click → QClaw → Ask AI** — Select code, right-click to send to AI for analysis
- **Ctrl+1 Shortcut** — Quick ask AI with selected code in editor
- **Streaming Output** — SSE-based real-time response display
- **Multi-turn Conversation** — Full chat history context maintained
- **Stop / Clear / Copy / Export** — Full conversation control
- **Language Detection** — Auto-detect C/C++/Assembly/Linker/SysConfig from file extension
- **Token Counter** — Display token usage in status bar

## Architecture

```
┌─────────────────────────────────────────┐
│           CCS 12.8.0 (Eclipse)          │
│  ┌─────────────────┐  ┌──────────────┐  │
│  │ AIAssistantView │  │ AskAIHandler │  │
│  │  (Chat Panel)   │←─│ (Right-click)│  │
│  └────────┬────────┘  └──────┬───────┘  │
│           │                  │           │
│           └──────┬───────────┘           │
│                  ▼                       │
│           ┌──────────────┐               │
│           │  AIClient    │               │
│           │ (HTTP/SSE)   │               │
│           └──────┬───────┘               │
└──────────────────┼──────────────────────┘
                   │ HTTP POST /v1/chat/completions
                   ▼
        ┌──────────────────┐
        │  OpenClaw Gateway │
        │  127.0.0.1:50264 │
        │  (OpenAI API)     │
        └──────────────────┘
```

## Prerequisites

- TI Code Composer Studio 12.8.0
- JDK 11+ (for compilation)
- OpenClaw Gateway running on `127.0.0.1:50264`

## Quick Install

1. **Build the JAR:**
   ```powershell
   cd scripts
   .\build.ps1
   ```

2. **Deploy to CCS:**
   ```powershell
   Copy-Item "output\com.qclaw.ccs.assistant_1.0.0.jar" "C:\ti\ccs1280\ccs\eclipse\plugins\"
   ```

3. **Register bundle** (add to `bundles.info`):
   ```
   com.qclaw.ccs.assistant,1.0.0,plugins/com.qclaw.ccs.assistant_1.0.0.jar,4,false
   ```

4. **Clean OSGi cache and restart CCS:**
   ```powershell
   Remove-Item "C:\ti\ccs1280\ccs\eclipse\configuration\org.eclipse.osgi" -Recurse -Force
   # Then start CCS
   ```

5. **Open the view:** Window → Show View → Other → QClaw → AI Assistant

## Configuration

Edit `AIClient.java` to change:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `GATEWAY_HOST` | `127.0.0.1` | Gateway host |
| `GATEWAY_PORT` | `50264` | Gateway port |
| `AUTH_TOKEN` | (set your token) | Bearer token for API auth |
| `MODEL` | `openclaw` | Model name |

## Project Structure

```
ccs-ai-assistant/
├── src/
│   └── com/qclaw/ccs/assistant/
│       ├── AIAssistantView.java    # Main chat panel (ViewPart)
│       ├── AIClient.java          # HTTP/SSE client for Gateway API
│       └── AskAIHandler.java      # Right-click + Ctrl+1 handler
├── META-INF/
│   └── MANIFEST.MF                # OSGi bundle manifest
├── plugin.xml                      # Eclipse plugin extensions
├── build.properties               # PDE build configuration
├── scripts/
│   └── build.ps1                  # Build script (JDK 11 target)
├── .gitignore
└── README.md
```

## Build from Source

The plugin requires CCS's Eclipse JARs on the classpath. Use the provided build script:

```powershell
.\scripts\build.ps1
```

This will:
1. Locate Eclipse plugin JARs in CCS installation
2. Compile all Java sources with `--release 11` target
3. Package into `output/com.qclaw.ccs.assistant_1.0.0.jar`
4. Deploy to CCS plugins directory

**Important:** The `--release 11` flag is mandatory — CCS runs on Java 11. Compiling with JDK >11 without this flag will cause `UnsupportedClassVersionError`.

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Plugin not visible in Show View | Clean `configuration/org.eclipse.osgi/` and restart CCS |
| `UnsupportedClassVersionError` | Ensure compilation uses `--release 11`; check for stale `.class` files in project `bin/` |
| Right-click menu missing | Ensure `plugin.xml` uses `popup:org.eclipse.ui.popup.any?after=additions` |
| Connection refused | Verify Gateway is running on configured host:port |
| View shows red error marker | Check CCS Error Log for missing `Require-Bundle` dependencies |

## License

MIT

## Author

[logicalmove](https://github.com/logicalmove)
