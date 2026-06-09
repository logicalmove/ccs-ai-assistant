# CCS AI Assistant

AI Assistant plugin for TI Code Composer Studio (CCS) 12.8.0.

Embed an AI chat assistant directly into your CCS editor with streaming responses,
right-click code analysis, keyboard shortcuts, compilation error diagnosis, and slash commands.

## Features

### Core (v1.0.0)
- **AI Chat Panel** -- StyledText output + text input, real-time streaming display
- **Right-Click Menu (QClaw > Ask AI)** -- Select code, right-click to send to AI
- **Ctrl+1 Shortcut** -- Quick ask AI with selected code in editor
- **Ctrl+2 Shortcut** -- Diagnose compilation errors with AI
- **Streaming Output** -- SSE-based real-time response display
- **Multi-turn Conversation** -- Full chat history context maintained
- **Stop / Clear / Copy / Export** -- Full conversation control
- **Language Detection** -- Auto-detect C/C++/Assembly/Linker/SysConfig from file extension
- **Token Counter** -- Display token usage in status bar

### Extended (v1.1.0)
- **Diagnose Errors** -- Reads CCS Problems view (IMarker), sends error report to AI for fix suggestions
  - Right-click > QClaw > Diagnose Errors
  - Ctrl+2 in editor
  - /fix slash command
- **Slash Commands** -- Quick actions from input box
  - /explain -- Explain selected code
  - /optimize -- Optimize selected code
  - /fix -- Fix compilation errors (reads IMarker)
  - /generate <desc> -- Generate code from description
  - /review -- Code review for bugs and improvements
  - /docs -- Generate Doxygen documentation
  - /convert <target> -- Convert code to another language
  - /help -- Show all commands
- **Token Usage** -- Real-time prompt/completion/total token display in status bar

## Architecture

`	ext
+-------------------------------------------------------+
|              CCS 12.8.0 (Eclipse 4.x)                |
|                                                         |
|  +--------------------+  +--------------------+        |
|  | AIAssistantView    |  | DiagnoseErrors     |        |
|  | (Chat Panel)       |  | Handler            |        |
|  +--------+-----------+  +---------+----------+        |
|           |                        |                   |
|  +--------+-----------+            |                   |
|  | AskAIHandler       |            |                   |
|  | (Right-click+Ctrl+1)|           |                   |
|  +--------+-----------+            |                   |
|           |                        |                   |
|           +----------+-------------+                   |
|                      |                                 |
|              +-------v--------+                        |
|              | AIClient       |                        |
|              | (HTTP/SSE)     |                        |
|              +-------+--------+                        |
|                      |                                 |
+----------------------+---------------------------------+
                       | HTTP POST /v1/chat/completions
                       v
              +------------------+
              | OpenClaw Gateway |
              | 127.0.0.1:50264  |
              | (OpenAI API)     |
              +------------------+
`

## Prerequisites

- TI Code Composer Studio 12.8.0
- JDK 11+ (for compilation)
- OpenClaw Gateway running on 127.0.0.1:50264

## Quick Install

1. **Build the JAR:**
   `powershell
   cd scripts
   .\build.ps1
   `

2. **The script automatically:**
   - Compiles all Java sources with --release 11 target
   - Packages into output/com.qclaw.ccs.assistant_1.1.0.jar
   - Deploys to CCS plugins directory
   - Updates undles.info (UTF-8 no BOM)
   - Cleans OSGi cache

3. **Restart CCS**

4. **Open the view:** Window > Show View > Other > QClaw > AI Assistant

## Configuration

Edit AIClient.java to change:

| Parameter   | Default     | Description               |
|-------------|-------------|---------------------------|
| GATEWAY_HOST | 127.0.0.1 | Gateway host            |
| GATEWAY_PORT | 50264    | Gateway port             |
| AUTH_TOKEN | (set your token) | Bearer token for API auth |
| MODEL      | openclaw  | Model name                |

## Project Structure

`	ext
ccs-ai-assistant/
+-- src/
|   +-- com/qclaw/ccs/assistant/
|       +-- AIAssistantView.java       # Main chat panel (ViewPart)
|       +-- AIClient.java             # HTTP/SSE client for Gateway API
|       +-- AskAIHandler.java         # Right-click + Ctrl+1 handler
|       +-- DiagnoseErrorsHandler.java # Error diagnosis + Ctrl+2 handler
+-- META-INF/
|   +-- MANIFEST.MF                   # OSGi bundle manifest
+-- plugin.xml                         # Eclipse plugin extensions
+-- build.properties                   # PDE build configuration
+-- scripts/
|   +-- build.ps1                      # Build + deploy script
+-- .gitignore
+-- README.md
+-- docs/
    +-- SKILL.md                       # Skill documentation
`

## Build from Source

The plugin requires CCS's Eclipse JARs on the classpath. Use the provided build script:

`powershell
.\scripts\build.ps1
`

**Important:** The --release 11 flag is mandatory -- CCS runs on Java 11.

## Troubleshooting & Error Reference

### Critical Errors (encountered during development)

| Error | Root Cause | Fix |
|-------|-----------|-----|
| UnsupportedClassVersionError: class file version 63.0 | Compiled with JDK 19 without --release 11 | Use --release 11 flag |
| Same error after fixing compilation | Stale .class files in in/ directory (CCS loads from project bin first) | Delete in/ directory, keep only JAR |
| Same error still persists | Old output.jar (JDK 19 build) still registered in undles.info | Delete output.jar, remove its entry from undles.info |
| IllegalArgumentException: Line does not contain at least 5 tokens | undles.info written with **UTF-8 BOM** (EF BB BF) | Rewrite file with UTF8Encoding(no BOM). **This crashes the entire CCS!** |
| View shows red error marker (createErrorPart) | plugin.xml missing from JAR package | Ensure build script copies plugin.xml into build dir before jar cfm |
| Right-click menu not showing | Handler missing ctiveWhen condition for CDT editors | Use popup:org.eclipse.ui.popup.any?after=additions and ctiveWhen with ctiveEditorId |
| IDocument class not found during compilation | org.eclipse.text JAR not on classpath (it's separate from org.eclipse.jface.text) | Add org.eclipse.text_* to classpath |
| ClassNotFoundException for bundle classes | Old compiled classes in project conflict with JAR | Clean all bin/ and target/ directories, only use JAR |

### General Tips

- **Always clean OSGi cache** after deploying a new JAR version
- **bundles.info MUST be UTF-8 without BOM** -- even 3 invisible bytes crash CCS
- **Only one JAR per bundle** -- don't leave old versions in plugins/
- **Delete project in/ directory** -- CCS may load stale classes from workspace
- **Verify JAR contents** with jar tf to ensure plugin.xml and META-INF/MANIFEST.MF are present

## License

MIT

## Author

[logicalmove](https://github.com/logicalmove)