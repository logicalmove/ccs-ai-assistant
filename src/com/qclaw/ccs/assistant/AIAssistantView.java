package com.qclaw.ccs.assistant;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIAssistantView extends ViewPart {
    public static final String ID = "com.qclaw.ccs.assistant.AIAssistantView";
    private StyledText outputArea;
    private Text inputField;
    private Button sendButton;
    private Button stopButton;
    private Button clearButton;
    private Button copyButton;
    private Button exportButton;
    private Label statusLabel;
    private AIClient aiClient;
    private boolean isProcessing = false;
    private Thread activeStreamThread;
    private int totalTokensUsed = 0;
    private int lastPromptTokens = 0;
    private int lastCompletionTokens = 0;
    private StringBuilder currentResponse = new StringBuilder();

    /** Slash commands: key -> description */
    private static final String[][] SLASH_COMMANDS = {
        {"/explain",   "解释当前选中的代码"},
        {"/optimize",  "优化当前选中的代码"},
        {"/fix",       "修复当前文件中的编译错误"},
        {"/generate",  "根据描述生成代码"},
        {"/review",    "代码审查：查找潜在问题和改进建议"},
        {"/docs",      "为选中的代码生成文档注释"},
        {"/convert",   "转换代码（如 C 转 C++）"},
        {"/help",      "显示所有可用命令"},
    };

    public void sendCodeToAI(String codeContext, String language) {
        String prompt;
        if (language != null && !language.isEmpty()) {
            prompt = "Please analyze/explain the following " + language + " code:\n\n" + codeContext;
        } else {
            prompt = "Please analyze/explain the following code:\n\n" + codeContext;
        }
        sendPromptToAI(prompt);
    }

    public void sendPromptToAI(String prompt) {
        if (isProcessing) return;
        if (outputArea.isDisposed()) return;

        isProcessing = true;
        currentResponse.setLength(0);
        lastPromptTokens = 0;
        lastCompletionTokens = 0;
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);
        inputField.setEnabled(false);
        sendButton.setText("Thinking...");
        updateStatus("Generating...");

        appendMessage("You: " + prompt);
        appendMessage("AI: ");

        aiClient.sendMessageStream(prompt, new AIClient.StreamCallback() {
            @Override
            public void onChunk(String text) {
                outputArea.getDisplay().asyncExec(() -> {
                    if (!outputArea.isDisposed()) {
                        appendText(text);
                        currentResponse.append(text);
                        outputArea.setSelection(outputArea.getText().length());
                    }
                });
            }

            @Override
            public void onComplete(String usage) {
                outputArea.getDisplay().asyncExec(() -> {
                    if (!outputArea.isDisposed()) {
                        appendText("\n");
                        // Parse usage
                        if (usage != null && !usage.isEmpty()) {
                            parseUsage(usage);
                        }
                    }
                    reEnableInput();
                });
            }

            @Override
            public void onError(String error) {
                outputArea.getDisplay().asyncExec(() -> {
                    if (!outputArea.isDisposed()) {
                        appendText("\nError: " + error + "\n");
                    }
                    reEnableInput();
                });
            }
        });
    }

    /** Parse usage JSON to extract token counts */
    private void parseUsage(String usageJson) {
        try {
            Pattern promptPat = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
            Pattern compPat = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");
            Pattern totalPat = Pattern.compile("\"total_tokens\"\\s*:\\s*(\\d+)");

            Matcher pm = promptPat.matcher(usageJson);
            Matcher cm = compPat.matcher(usageJson);
            Matcher tm = totalPat.matcher(usageJson);

            if (pm.find()) lastPromptTokens = Integer.parseInt(pm.group(1));
            if (cm.find()) lastCompletionTokens = Integer.parseInt(cm.group(1));
            if (tm.find() && !pm.find()) {
                // Some APIs only return total_tokens
                totalTokensUsed = Integer.parseInt(tm.group(1));
            } else {
                totalTokensUsed += lastPromptTokens + lastCompletionTokens;
            }
            updateStatus("Ready");
        } catch (Exception e) {
            // usage parsing failed silently
        }
    }

    public void stopGeneration() {
        if (activeStreamThread != null && activeStreamThread.isAlive()) {
            activeStreamThread.interrupt();
            activeStreamThread = null;
        }
        if (!outputArea.isDisposed()) {
            appendText("\n[Stopped]\n");
        }
        reEnableInput();
    }

    public void clearChat() {
        if (isProcessing) stopGeneration();
        outputArea.setText(buildWelcomeMessage());
        aiClient.clearHistory();
        totalTokensUsed = 0;
        lastPromptTokens = 0;
        lastCompletionTokens = 0;
        updateStatus("Ready");
        inputField.setFocus();
    }

    public void copyLastResponse() {
        String text = currentResponse.toString().trim();
        if (text.isEmpty()) {
            text = outputArea.getSelectionText();
        }
        if (text.isEmpty()) {
            updateStatus("Nothing to copy");
            return;
        }
        Clipboard clipboard = new Clipboard(Display.getCurrent());
        clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
        clipboard.dispose();
        updateStatus("Copied to clipboard");
    }

    public void exportChat() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
        dialog.setFilterNames(new String[] { "Text Files (*.txt)", "All Files (*.*)" });
        dialog.setFilterExtensions(new String[] { "*.txt", "*.*" });
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        dialog.setFileName("ccs_ai_chat_" + timestamp + ".txt");
        String path = dialog.open();
        if (path == null) return;

        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(outputArea.getText().getBytes("UTF-8"));
            updateStatus("Exported to: " + path);
        } catch (Exception e) {
            updateStatus("Export failed: " + e.getMessage());
        }
    }

    private void reEnableInput() {
        if (!sendButton.isDisposed()) {
            isProcessing = false;
            activeStreamThread = null;
            sendButton.setEnabled(true);
            inputField.setEnabled(true);
            stopButton.setEnabled(false);
            sendButton.setText("Send");
            updateStatus("Ready");
            inputField.setFocus();
        }
    }

    private void updateStatus(String text) {
        if (statusLabel != null && !statusLabel.isDisposed()) {
            String tokenInfo = " | Tokens: " + totalTokensUsed;
            if (lastPromptTokens > 0 || lastCompletionTokens > 0) {
                tokenInfo += " (+" + lastPromptTokens + " prompt, " + lastCompletionTokens + " completion)";
            }
            statusLabel.setText(text + tokenInfo);
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        parent.setLayout(layout);

        aiClient = new AIClient();

        // Output area
        outputArea = new StyledText(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
        outputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        outputArea.setText(buildWelcomeMessage());
        outputArea.setEditable(false);

        // Button bar
        Composite buttonComposite = new Composite(parent, SWT.NONE);
        GridLayout btnLayout = new GridLayout(6, false);
        btnLayout.marginWidth = 0;
        btnLayout.marginHeight = 5;
        buttonComposite.setLayout(btnLayout);
        buttonComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        inputField = new Text(buttonComposite, SWT.BORDER | SWT.SINGLE);
        inputField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        inputField.setMessage("Type /help for commands...");

        sendButton = new Button(buttonComposite, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.addListener(SWT.Selection, e -> sendMessage());

        stopButton = new Button(buttonComposite, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, e -> stopGeneration());

        copyButton = new Button(buttonComposite, SWT.PUSH);
        copyButton.setText("Copy");
        copyButton.addListener(SWT.Selection, e -> copyLastResponse());

        exportButton = new Button(buttonComposite, SWT.PUSH);
        exportButton.setText("Export");
        exportButton.addListener(SWT.Selection, e -> exportChat());

        clearButton = new Button(buttonComposite, SWT.PUSH);
        clearButton.setText("Clear");
        clearButton.addListener(SWT.Selection, e -> clearChat());

        // Handle Enter key and Slash commands
        inputField.addListener(SWT.DefaultSelection, e -> sendMessage());
        inputField.addListener(SWT.Verify, e -> {
            // Auto-complete hint for slash commands
        });

        // Status bar
        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("Ready");
    }

    private String buildWelcomeMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CCS AI Assistant ===\n\n");
        sb.append("Welcome to CCS AI Assistant!\n\n");
        sb.append("Quick Actions:\n");
        sb.append("  - Select code, right-click > QClaw > Ask AI\n");
        sb.append("  - Select code, press Ctrl+1 to analyze\n");
        sb.append("  - Right-click > QClaw > Diagnose Errors\n\n");
        sb.append("Slash Commands:\n");
        for (String[] cmd : SLASH_COMMANDS) {
            sb.append(String.format("  %-12s %s\n", cmd[0], cmd[1]));
        }
        sb.append("\nCtrl+1 in editor: Quick ask AI with selected code\n\n");
        return sb.toString();
    }

    /** Handle slash commands */
    private boolean handleSlashCommand(String input) {
        if (!input.startsWith("/")) return false;

        String cmd = input.trim().toLowerCase();
        String args = "";

        // Split command and arguments
        int spaceIdx = input.indexOf(' ');
        if (spaceIdx > 0) {
            cmd = input.substring(0, spaceIdx).toLowerCase();
            args = input.substring(spaceIdx + 1).trim();
        }

        switch (cmd) {
            case "/help":
                showHelp();
                return true;

            case "/explain":
                sendWithEditorContext("请详细解释以下代码的功能、逻辑和关键点：\n\n", args);
                return true;

            case "/optimize":
                sendWithEditorContext("请优化以下代码，提高性能和可读性，并说明修改原因：\n\n", args);
                return true;

            case "/fix":
                sendDiagnoseErrors();
                return true;

            case "/generate":
                if (args.isEmpty()) {
                    appendMessage("System: Usage: /generate <description>\nExample: /generate C2000 GPIO toggle function");
                    return true;
                }
                sendPromptToAI("请根据以下描述生成TI C2000嵌入式C代码，包含必要的头文件和注释：\n\n" + args);
                return true;

            case "/review":
                sendWithEditorContext("请对以下代码进行审查，找出潜在bug、安全问题、性能问题和改进建议：\n\n", args);
                return true;

            case "/docs":
                sendWithEditorContext("请为以下代码生成完整的文档注释（Doxygen风格），包括函数说明、参数、返回值和示例：\n\n", args);
                return true;

            case "/convert":
                if (args.isEmpty()) {
                    appendMessage("System: Usage: /convert <target>\nExample: /convert C++, /convert Assembly, /convert Rust");
                    return true;
                }
                sendWithEditorContext("请将以下代码转换为" + args + "，保持功能等价：\n\n", "");
                return true;

            default:
                appendMessage("System: Unknown command: " + cmd + "\nType /help for available commands.");
                return true;
        }
    }

    /** Show help message */
    private void showHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Available Commands ===\n\n");
        for (String[] cmd : SLASH_COMMANDS) {
            sb.append(String.format("  %-12s %s\n", cmd[0], cmd[1]));
        }
        sb.append("\nTips:\n");
        sb.append("  - /explain, /optimize, /review, /docs use selected code in editor\n");
        sb.append("  - If no code selected, the entire file content is used\n");
        sb.append("  - /fix reads Problems view for compilation errors\n");
        sb.append("  - /generate creates code from a description\n");
        appendMessage("System: " + sb.toString());
    }

    /** Send command with current editor's selected text (or full file) as context */
    private void sendWithEditorContext(String prefix, String extraArgs) {
        String code = getActiveEditorSelection();
        if (code.isEmpty()) {
            appendMessage("System: No editor open or no code found. Open a C/C++ file and select code first.");
            return;
        }
        String prompt = prefix + "```" + detectEditorLanguage() + "\n" + code + "\n```\n";
        if (!extraArgs.isEmpty()) {
            prompt += "\nAdditional requirements: " + extraArgs;
        }
        sendPromptToAI(prompt);
    }

    /** Trigger error diagnosis (same as DiagnoseErrorsHandler) */
    private void sendDiagnoseErrors() {
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;
            org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
            if (page == null) return;

            org.eclipse.ui.IEditorPart editor = page.getActiveEditor();
            if (editor == null) {
                appendMessage("System: No editor open. Open a file with errors first.");
                return;
            }

            org.eclipse.core.resources.IResource resource =
                editor.getEditorInput().getAdapter(org.eclipse.core.resources.IResource.class);
            if (resource == null) {
                appendMessage("System: Cannot access file resources.");
                return;
            }

            java.util.List<ErrorInfo> errors = new java.util.ArrayList<>();
            org.eclipse.core.resources.IMarker[] markers =
                resource.findMarkers(org.eclipse.core.resources.IMarker.PROBLEM, true,
                    org.eclipse.core.resources.IResource.DEPTH_ZERO);

            for (org.eclipse.core.resources.IMarker marker : markers) {
                int severity = marker.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
                    org.eclipse.core.resources.IMarker.SEVERITY_INFO);
                if (severity == org.eclipse.core.resources.IMarker.SEVERITY_ERROR
                    || severity == org.eclipse.core.resources.IMarker.SEVERITY_WARNING) {
                    errors.add(new ErrorInfo(marker));
                }
            }

            if (errors.isEmpty()) {
                appendMessage("System: No errors/warnings found. Build the project first (Project > Build All).");
                return;
            }

            StringBuilder report = new StringBuilder();
            report.append("编译错误诊断 - ").append(editor.getEditorInput().getName());
            report.append(" 中发现 ").append(errors.size()).append(" 个问题：\n\n");
            for (int i = 0; i < errors.size(); i++) {
                ErrorInfo e = errors.get(i);
                report.append(e.severity).append(" #").append(i + 1);
                if (e.lineNumber > 0) report.append(" (行 ").append(e.lineNumber).append(")");
                report.append(": ").append(e.message).append("\n");
            }
            report.append("\n请逐个分析错误原因并给出修复建议。");

            // Attach source
            try {
                org.eclipse.ui.texteditor.ITextEditor te =
                    (editor instanceof org.eclipse.ui.texteditor.ITextEditor)
                        ? (org.eclipse.ui.texteditor.ITextEditor) editor
                        : editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
                if (te != null) {
                    org.eclipse.ui.IEditorInput editorInput = te.getEditorInput();
                    String src = te.getDocumentProvider().getDocument(editorInput).get();
                    report.append("\n\n--- 源代码 ---\n").append(src);
                }
            } catch (Exception ex) { /* ok */ }

            sendPromptToAI(report.toString());

        } catch (Exception e) {
            appendMessage("System: Error reading markers: " + e.getMessage());
        }
    }

    /** Get selected text or full file from active editor */
    private String getActiveEditorSelection() {
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return "";
            org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
            if (page == null) return "";
            org.eclipse.ui.IEditorPart editor = page.getActiveEditor();
            if (editor == null) return "";

            org.eclipse.ui.texteditor.ITextEditor te =
                (editor instanceof org.eclipse.ui.texteditor.ITextEditor)
                    ? (org.eclipse.ui.texteditor.ITextEditor) editor
                    : editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
            if (te == null) return "";

            org.eclipse.jface.text.ITextSelection sel =
                (org.eclipse.jface.text.ITextSelection) te.getSelectionProvider().getSelection();
            String text = sel.getText();
            if (text != null && !text.trim().isEmpty()) return text;

            // No selection → full file
            org.eclipse.ui.IEditorInput editorInput = te.getEditorInput();
            return te.getDocumentProvider().getDocument(editorInput).get();
        } catch (Exception e) {
            return "";
        }
    }

    /** Detect language from active editor file extension */
    private String detectEditorLanguage() {
        try {
            org.eclipse.ui.IEditorPart editor =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                    .getActivePage().getActiveEditor();
            if (editor == null) return "c";
            String name = editor.getEditorInput().getName();
            if (name == null) return "c";
            if (name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx")
                || name.endsWith(".hpp") || name.endsWith(".hxx")) return "cpp";
            if (name.endsWith(".h") || name.endsWith(".H")) return "c";
            if (name.endsWith(".asm") || name.endsWith(".s")) return "asm";
            if (name.endsWith(".cmd")) return "linker";
            if (name.endsWith(".syscfg")) return "syscfg";
            return "c";
        } catch (Exception e) {
            return "c";
        }
    }

    private void sendMessage() {
        if (isProcessing) return;
        String message = inputField.getText().trim();
        if (message.isEmpty()) return;
        inputField.setText("");

        // Check for slash commands
        if (handleSlashCommand(message)) return;

        sendPromptToAI(message);
    }

    private void appendMessage(String message) {
        String current = outputArea.getText();
        if (!current.isEmpty()) {
            current += "\n\n";
        }
        outputArea.setText(current + message);
        outputArea.setSelection(outputArea.getText().length());
    }

    private void appendText(String text) {
        outputArea.append(text);
    }

    public static AIAssistantView getInstance() {
        try {
            return (AIAssistantView) org.eclipse.ui.PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getActivePage()
                .findView(ID);
        } catch (Exception e) {
            return null;
        }
    }

    /** Internal error info holder for marker data */
    private static class ErrorInfo {
        String severity;
        String message;
        int lineNumber;

        ErrorInfo(org.eclipse.core.resources.IMarker marker) throws org.eclipse.core.runtime.CoreException {
            int sev = marker.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY,
                org.eclipse.core.resources.IMarker.SEVERITY_INFO);
            this.severity = (sev == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) ? "ERROR" : "WARNING";
            this.message = marker.getAttribute(org.eclipse.core.resources.IMarker.MESSAGE, "(no message)");
            this.lineNumber = marker.getAttribute(org.eclipse.core.resources.IMarker.LINE_NUMBER, 0);
        }
    }

    @Override
    public void setFocus() {
        if (inputField != null && !inputField.isDisposed()) {
            inputField.setFocus();
        }
    }
}
