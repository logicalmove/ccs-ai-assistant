---
name: ccs-ai-assistant
description: |
  Eclipse plugin for TI CCS 12.8.0 that embeds an AI assistant (OpenClaw Gateway).
  Features: chat panel, right-click code analysis (Ctrl+1), error diagnosis (Ctrl+2),
  slash commands, streaming output, token usage display.
---

# CCS AI Assistant -- Skill Documentation

## Overview

An Eclipse plugin that integrates an AI assistant into TI Code Composer Studio 12.8.0.
Connects to OpenClaw Gateway via OpenAI-compatible API for code analysis, error diagnosis,
and code generation.

## Architecture

- **AIAssistantView** (ViewPart) -- Main chat panel with StyledText output/input, buttons, status bar
- **AIClient** -- HTTP/SSE client, OpenAI API compatible, handles streaming responses and usage tracking
- **AskAIHandler** -- Ctrl+1 / right-click handler, sends selected code to AI
- **DiagnoseErrorsHandler** -- Ctrl+2 / right-click handler, reads IMarker errors from CCS Problems view

## Key Technical Details

### Gateway Connection
- Endpoint: http://127.0.0.1:50264/v1/chat/completions
- Model: openclaw
- Auth: Bearer token
- Streaming: SSE with stream_options.include_usage: true

### Build Requirements
- **Must compile with --release 11** -- CCS runs on Java 11
- **undles.info must be UTF-8 without BOM** -- BOM crashes entire CCS
- **plugin.xml must be inside JAR** -- Without it, no extensions are registered
- **Classpath needs 26 Eclipse JARs** -- Explicitly listed in build.ps1

### Slash Commands
/explain /optimize /fix /generate /review /docs /convert /help

### Keyboard Shortcuts
- Ctrl+1 -- Ask AI about selected code
- Ctrl+2 -- Diagnose compilation errors

## Error Reference (Development Lessons)

1. **Java 11 target required** -- UnsupportedClassVersionError if compiled for higher JDK
2. **Clean bin/ directory** -- CCS loads classes from workspace in/ before JAR
3. **bundles.info NO BOM** -- UTF8Encoding(False) in PowerShell, 
ew UTF8Encoding(false) in C#
4. **plugin.xml in JAR** -- Copy to build dir before jar cfm
5. **Only one JAR per bundle** -- Remove old versions from plugins/
6. **org.eclipse.text is separate** -- Not in jface.text JAR, needs own classpath entry

## Files

| File | Purpose |
|------|---------|
| src/.../AIAssistantView.java | Chat panel UI, slash commands, token tracking |
| src/.../AIClient.java | HTTP/SSE Gateway client |
| src/.../AskAIHandler.java | Ctrl+1 + right-click Ask AI |
| src/.../DiagnoseErrorsHandler.java | Ctrl+2 + right-click error diagnosis |
| plugin.xml | Eclipse extensions (views, commands, menus, bindings) |
| META-INF/MANIFEST.MF | OSGi bundle manifest |
| scripts/build.ps1 | Full build + deploy + cleanup script |