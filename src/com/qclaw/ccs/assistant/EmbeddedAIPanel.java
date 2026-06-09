package com.qclaw.ccs.assistant;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.dnd.*;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.part.*;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.jface.text.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Embedded AI panel that integrates directly into the CCS editor area.
 * Shows as a resizable split pane below the code editor.
 * Controlled via the main AIAssistantView toolbar.
 */
public class EmbeddedAIPanel {
    
    private Composite parentComposite;
    private SashForm sashForm;
    private StyledText outputArea;
    private Text inputField;
    private Button sendButton;
    private Button stopButton;
    private Label statusLabel;
    private AIClient aiClient;
    private StringBuilder currentResponse = new StringBuilder();
    private MarkdownRenderer markdownRenderer;
    private CCSProjectManager projectManager;
    private CCSDebugManager debugManager;
    private volatile boolean isProcessing = false;
    private Thread activeStreamThread;
    private AIAssistantView mainView;
    
    public EmbeddedAIPanel(AIAssistantView mainView, AIClient aiClient) {
        this.mainView = mainView;
        this.aiClient = aiClient;
        this.projectManager = new CCSProjectManager(aiClient);
        this.debugManager = new CCSDebugManager(aiClient);
    }
    
    /**
     * Create or toggle the embedded AI panel in the active editor area.
     */
    public void togglePanel() {
        if (sashForm != null && !sashForm.isDisposed()) {
            // Toggle visibility
            boolean visible = sashForm.getVisible();
            if (visible) {
                sashForm.setVisible(false);
                // Restore editor to full size
                ((GridData)sashForm.getLayoutData()).exclude = true;
                sashForm.getParent().layout(true);
            } else {
                sashForm.setVisible(true);
                ((GridData)sashForm.getLayoutData()).exclude = false;
                sashForm.getParent().layout(true);
                inputField.setFocus();
            }
        }
    }
    
    /**
     * Embed the AI panel into the given composite (editor area).
     */
    public void createPanel(Composite parent) {
        this.parentComposite = parent;
        dispose();
        
        sashForm = new SashForm(parent, SWT.HORIZONTAL | SWT.BOTTOM);
        GridData gd = new GridData(SWT.FILL, SWT.END, true, true);
        gd.heightHint = 250;
        gd.exclude = false;
        sashForm.setLayoutData(gd);
        sashForm.setBackground(new Color(parent.getDisplay(), new RGB(45, 45, 48)));
        
        // Left: output area
        Composite outputComposite = new Composite(sashForm, SWT.NONE);
        GridLayout outputLayout = new GridLayout(1, false);
        outputLayout.marginWidth = 3;
        outputLayout.marginHeight = 3;
        outputComposite.setLayout(outputLayout);
        outputComposite.setBackground(new Color(parent.getDisplay(), new RGB(45, 45, 48)));
        
        // Output header
        Label headerLabel = new Label(outputComposite, SWT.NONE);
        headerLabel.setText("QClaw AI (Embedded)");
        headerLabel.setForeground(new Color(parent.getDisplay(), new RGB(0, 200, 150)));
        headerLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, true, false));
        headerLabel.setBackground(new Color(parent.getDisplay(), new RGB(45, 45, 48)));
        
        outputArea = new StyledText(outputComposite, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL
            | SWT.H_SCROLL | SWT.WRAP | SWT.READ_ONLY);
        outputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        outputArea.setBackground(new Color(parent.getDisplay(), new RGB(30, 30, 30)));
        outputArea.setForeground(new Color(parent.getDisplay(), new RGB(212, 212, 212)));
        outputArea.setFont(new Font(parent.getDisplay(), "Consolas", 10, SWT.NORMAL));
        
        // Markdown rendering
        applyMarkdownRendering();
        
        // Mouse click for insert links
        outputArea.addMouseListener(new MouseListener() {
            public void mouseDoubleClick(MouseEvent e) {}
            public void mouseDown(MouseEvent e) {}
            public void mouseUp(MouseEvent e) {
                try {
                    int offset = outputArea.getOffsetAtLocation(new Point(e.x, e.y));
                    StyleRange style = outputArea.getStyleRangeAtOffset(offset);
                    if (style != null && style.underline && style.data instanceof Integer) {
                        handleInsertLinkClick(offset);
                    }
                } catch (Exception ex) { /* not on text */ }
            }
        });
        
        // Right: input area + controls
        Composite inputComposite = new Composite(sashForm, SWT.NONE);
        GridLayout inputLayout = new GridLayout(2, false);
        inputLayout.marginWidth = 3;
        inputLayout.marginHeight = 3;
        inputComposite.setLayout(inputLayout);
        inputComposite.setBackground(new Color(parent.getDisplay(), new RGB(45, 45, 48)));
        
        // Quick action buttons
        Composite btnBar = new Composite(inputComposite, SWT.NONE);
        RowLayout btnRow = new RowLayout(SWT.HORIZONTAL);
        btnRow.spacing = 3;
        btnRow.wrap = true;
        btnBar.setLayout(btnRow);
        btnBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        btnBar.setBackground(new Color(parent.getDisplay(), new RGB(45, 45, 48)));
        
        createQuickButton(btnBar, "Ask AI", new org.eclipse.swt.events.SelectionAdapter() { public void widgetSelected(SelectionEvent e) { askAI(); } });
        createQuickButton(btnBar, "Fix Errors", new org.eclipse.swt.events.SelectionAdapter() { public void widgetSelected(SelectionEvent e) { fixErrors(); } });
        createQuickButton(btnBar, "Import Project", new org.eclipse.swt.events.SelectionAdapter() { public void widgetSelected(SelectionEvent e) { importProjectDialog(); } });
        createQuickButton(btnBar, "Build", new org.eclipse.swt.events.SelectionAdapter() { public void widgetSelected(SelectionEvent e) { buildCurrentProject(); } });
        createQuickButton(btnBar, "Debug", new org.eclipse.swt.events.SelectionAdapter() { public void widgetSelected(SelectionEvent e) { startDebug(); } });
        
        // Input field
        inputField = new Text(inputComposite, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        inputField.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        inputField.setBackground(new Color(parent.getDisplay(), new RGB(35, 35, 35)));
        inputField.setForeground(new Color(parent.getDisplay(), new RGB(220, 220, 220)));
        inputField.setFont(new Font(parent.getDisplay(), "Consolas", 10, SWT.NORMAL));
        
        // Enter key to send
        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.CR && e.stateMask == SWT.NONE) {
                    e.doit = false;
                    sendMessage();
                }
            }
        });
        
        // Send/Stop buttons
        Composite sendBar = new Composite(inputComposite, SWT.NONE);
        GridLayout sendLayout = new GridLayout(1, false);
        sendLayout.marginWidth = 0;
        sendBar.setLayout(sendLayout);
        sendBar.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, false, false));
        sendBar.setBackground(new Color(parent.getDisplay(), new RGB(45, 45, 48)));
        
        sendButton = new Button(sendBar, SWT.PUSH);
        sendButton.setText("Send");
        sendButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        sendButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { sendMessage(); }
        });
        
        stopButton = new Button(sendBar, SWT.PUSH);
        stopButton.setText("Stop");
        stopButton.setEnabled(false);
        stopButton.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        stopButton.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { stopProcessing(); }
        });
        
        // Status bar
        statusLabel = new Label(inputComposite, SWT.NONE);
        statusLabel.setText("Ready");
        statusLabel.setForeground(new Color(parent.getDisplay(), new RGB(150, 150, 150)));
        statusLabel.setLayoutData(new GridData(SWT.LEFT, SWT.BOTTOM, true, false, 2, 1));
        statusLabel.setBackground(new Color(parent.getDisplay(), new RGB(45, 45, 48)));
        
        // Sash weights (70% editor, 30% AI)
        sashForm.setWeights(new int[]{70, 30});
        
        // Load history
        loadHistory();
    }
    
    private void createQuickButton(Composite parent, String text, SelectionAdapter listener) {
        Button btn = new Button(parent, SWT.PUSH | SWT.FLAT);
        btn.setText(text);
        btn.setSize(80, 24);
        btn.setForeground(new Color(parent.getDisplay(), new RGB(200, 200, 200)));
        btn.setBackground(new Color(parent.getDisplay(), new RGB(60, 60, 65)));
        btn.addSelectionListener(listener);
    }
    
    // ============================================================
    // Quick Actions
    // ============================================================
    
    private void askAI() {
        String selected = getSelectedCode();
        if (!selected.isEmpty()) {
            sendToAI("/explain " + selected);
        } else {
            if (inputField.isVisible()) inputField.setFocus();
        }
    }
    
    private void fixErrors() {
        appendMessage("System: Starting auto-fix workflow...");
        sendToAI("/fix");
    }
    
    private void importProjectDialog() {
        appendMessage("System: Searching for CCS projects...\nUse /import <pattern> to import, e.g. /import *blink*");
        // Auto-search
        String[] roots = {"C:\\ti", "C:\\workspace"};
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(".project");
        int found = 0;
        for (String root : roots) {
            File dir = new File(root);
            if (!dir.exists()) continue;
            try {
                File[] subs = dir.listFiles(File::isDirectory);
                if (subs != null) {
                    for (File sub : subs) {
                        if (new File(sub, ".project").exists()) {
                            appendMessage("  Found: " + sub.getAbsolutePath());
                            found++;
                            if (found >= 10) break;
                        }
                    }
                }
            } catch (Exception e) { /* skip */ }
            if (found >= 10) break;
        }
        if (found == 0) {
            appendMessage("System: No projects found. Use /import <path> to import from a specific location.");
        }
    }
    
    private void buildCurrentProject() {
        String projectName = getActiveProjectName();
        if (projectName.isEmpty()) {
            appendMessage("System: No active project. Open a project in CCS first.");
            return;
        }
        appendMessage("System: Building " + projectName + "...");
        
        try {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            projectManager.buildProject(project, new NullProgressMonitor());
        } catch (Exception e) {
            appendMessage("System: Build error - " + e.getMessage());
        }
    }
    
    private void startDebug() {
        String projectName = getActiveProjectName();
        if (projectName.isEmpty()) {
            appendMessage("System: No active project. Open a project in CCS first.");
            return;
        }
        String report = debugManager.runDebugCycle(projectName);
        appendMessage("System: " + report);
    }
    
    // ============================================================
    // Message Sending
    // ============================================================
    
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;
        
        // Handle slash commands
        if (text.startsWith("/")) {
            if (handleSlashCommand(text)) {
                inputField.setText("");
                return;
            }
        }
        
        sendToAI(text);
        inputField.setText("");
    }
    
    private void sendToAI(String prompt) {
        if (isProcessing) return;
        isProcessing = true;
        currentResponse.setLength(0);
        sendButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusLabel.setText("Generating...");
        
        appendMessage("You: " + (prompt.length() > 200 ? prompt.substring(0, 200) + "..." : prompt));
        appendMessage("AI: ");
        
        activeStreamThread = new Thread(() -> {
            aiClient.sendMessageStream(prompt, new AIClient.StreamCallback() {
                @Override public void onChunk(String t) {
                    currentResponse.append(t);
                    if (!outputArea.isDisposed()) {
                        outputArea.getDisplay().asyncExec(() -> {
                            if (!outputArea.isDisposed()) {
                                outputArea.append(t);
                                outputArea.setCaretOffset(outputArea.getCharCount());
                            }
                        });
                    }
                }
                @Override public void onComplete(String usage) {
                    if (!outputArea.isDisposed()) {
                        outputArea.getDisplay().asyncExec(() -> {
                            if (!outputArea.isDisposed()) {
                                addCodeBlockInsertLinks();
                                reEnableInput();
                                refreshMarkdown();
                            }
                        });
                    }
                }
                @Override public void onError(String error) {
                    if (!outputArea.isDisposed()) {
                        outputArea.getDisplay().asyncExec(() -> {
                            if (!outputArea.isDisposed()) {
                                outputArea.append("\nError: " + error + "\n");
                                reEnableInput();
                                refreshMarkdown();
                            }
                        });
                    }
                }
            });
        });
        activeStreamThread.start();
    }
    
    // ============================================================
    // Slash Commands (embedded-specific)
    // ============================================================
    
    private boolean handleSlashCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";
        
        switch (command) {
            case "/import":
                importProject(args);
                return true;
            case "/build":
                buildCurrentProject();
                return true;
            case "/debug":
                startDebug();
                return true;
            case "/targets":
                appendMessage(debugManager.detectAvailableTargets());
                return true;
            case "/auto":
                runAutoWorkflow(args);
                return true;
            case "/clear":
                if (!outputArea.isDisposed()) outputArea.setText("");
                return true;
            case "/help":
                showEmbeddedHelp();
                return true;
            default:
                // Pass to AI
                return false;
        }
    }
    
    private void importProject(String pattern) {
        if (pattern.isEmpty()) {
            appendMessage("System: Use /import <pattern> e.g. /import *blink* or /import C:\\path\\to\\project");
            return;
        }
        
        IProgressMonitor monitor = new NullProgressMonitor();
        java.util.List<java.io.File> found = projectManager.searchProjects(pattern, monitor);
        
        if (found.isEmpty()) {
            appendMessage("System: No projects found for '" + pattern + "'");
            return;
        }
        
        appendMessage("System: Found " + found.size() + " project(s), importing...");
        IProject imported = projectManager.importProject(found.get(0), monitor);
        if (imported != null) {
            appendMessage("System: Project '" + imported.getName() + "' imported successfully.");
        } else {
            appendMessage("System: Import failed.");
        }
    }
    
    private void runAutoWorkflow(String pattern) {
        if (pattern.isEmpty()) {
            appendMessage("System: Use /auto <project_pattern> to run import -> build -> AI fix cycle");
            return;
        }
        
        Thread worker = new Thread(() -> {
            IProgressMonitor monitor = new NullProgressMonitor();
            projectManager.runAutoWorkflow(pattern, monitor);
        });
        worker.start();
    }
    
    private void showEmbeddedHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== QClaw Embedded AI Commands ===\n\n");
        sb.append("Project Commands:\n");
        sb.append("  /import <pattern>   Search and import CCS project\n");
        sb.append("  /build              Build current project\n");
        sb.append("  /auto <pattern>     Auto workflow: import+build+AI fix+rebuild\n\n");
        sb.append("Debug Commands:\n");
        sb.append("  /debug              Start debug session for current project\n");
        sb.append("  /targets            List available debug targets\n\n");
        sb.append("AI Commands:\n");
        sb.append("  /explain            Explain selected code\n");
        sb.append("  /fix                 Auto-fix build errors\n");
        sb.append("  /watch <var=val>    Interpret debug register values\n");
        sb.append("  /errno <#code>      Decode TI compiler errors\n");
        sb.append("  /reg <register>     Query register bit fields\n\n");
        sb.append("General:\n");
        sb.append("  /clear              Clear output\n");
        sb.append("  /help               This help\n");
        appendMessage(sb.toString());
    }
    
    // ============================================================
    // Helpers
    // ============================================================
    
    private void appendMessage(String msg) {
        if (!outputArea.isDisposed()) {
            outputArea.append(msg + "\n");
            outputArea.setCaretOffset(outputArea.getCharCount());
        }
    }
    
    private String getSelectedCode() {
        try {
            IEditorPart editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                .getActivePage().getActiveEditor();
            if (editor instanceof ITextEditor) {
                ITextSelection sel = (ITextSelection) ((ITextEditor) editor)
                    .getSelectionProvider().getSelection();
                return sel.getText();
            }
        } catch (Exception e) { /* ignore */ }
        return "";
    }
    
    private String getActiveProjectName() {
        try {
            IEditorPart editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                .getActivePage().getActiveEditor();
            if (editor != null) {
                return editor.getEditorInput().getName();
            }
        } catch (Exception e) { /* ignore */ }
        return "";
    }
    
    private void reEnableInput() {
        isProcessing = false;
        if (!sendButton.isDisposed()) sendButton.setEnabled(true);
        if (!stopButton.isDisposed()) stopButton.setEnabled(false);
        if (!inputField.isDisposed()) inputField.setFocus();
        if (!statusLabel.isDisposed()) statusLabel.setText("Ready");
    }
    
    private void stopProcessing() {
        if (activeStreamThread != null && activeStreamThread.isAlive()) {
            activeStreamThread.interrupt();
        }
        reEnableInput();
    }
    
    private void loadHistory() {
        String history = aiClient.getHistoryFilePath();
        if (history != null) {
            try {
                java.io.File hf = new java.io.File(history);
                if (hf.exists()) {
                    String content = new String(java.nio.file.Files.readAllBytes(hf.toPath()), "UTF-8");
                    if (!content.isEmpty() && !outputArea.isDisposed()) {
                        outputArea.append(content);
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
    }
    
    private void applyMarkdownRendering() {
        if (markdownRenderer != null) markdownRenderer.dispose();
        markdownRenderer = new MarkdownRenderer(outputArea.getDisplay(), outputArea.getText());
        outputArea.removeLineStyleListener(markdownRenderer);
        outputArea.addLineStyleListener(markdownRenderer);
    }
    
    private void refreshMarkdown() {
        if (!outputArea.isDisposed()) applyMarkdownRendering();
    }
    
    private void addCodeBlockInsertLinks() {
        String response = currentResponse.toString();
        java.util.List<String> codeBlocks = extractCodeBlocks(response);
        if (codeBlocks.isEmpty()) return;
        
        String text = outputArea.getText();
        StringBuilder linksSb = new StringBuilder();
        for (int i = 0; i < codeBlocks.size(); i++) {
            linksSb.append("\n  \u200B@INSERT_BLOCK_").append(i).append("@ \u200B");
        }
        outputArea.append(linksSb.toString());
        
        String fullText = outputArea.getText();
        java.util.regex.Pattern markerPat = java.util.regex.Pattern.compile("@INSERT_BLOCK_(\\d+)@");
        Matcher markerMat = markerPat.matcher(fullText);
        while (markerMat.find()) {
            int start = markerMat.start();
            int end = markerMat.end();
            int blockIdx = Integer.parseInt(markerMat.group(1));
            StyleRange style = new StyleRange();
            style.start = start;
            style.length = end - start;
            style.foreground = new Color(outputArea.getDisplay(), new RGB(0, 150, 255));
            style.underline = true;
            style.underlineStyle = SWT.UNDERLINE_LINK;
            style.data = blockIdx;
            outputArea.setStyleRange(style);
        }
    }
    
    private void handleInsertLinkClick(int offset) {
        try {
            StyleRange style = outputArea.getStyleRangeAtOffset(offset);
            if (style != null && style.data instanceof Integer) {
                int blockIdx = (Integer) style.data;
                java.util.List<String> codeBlocks = extractCodeBlocks(currentResponse.toString());
                if (blockIdx >= 0 && blockIdx < codeBlocks.size()) {
                    insertCodeToEditor(codeBlocks.get(blockIdx));
                }
            }
        } catch (Exception e) {
            statusLabel.setText("Insert failed");
        }
    }
    
    private void insertCodeToEditor(String code) {
        try {
            IEditorPart editor = PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                .getActivePage().getActiveEditor();
            if (editor == null) return;
            
            ITextEditor te = (editor instanceof ITextEditor)
                ? (ITextEditor) editor
                : editor.getAdapter(ITextEditor.class);
            if (te == null) return;
            
            org.eclipse.jface.text.IDocument doc = te.getDocumentProvider()
                .getDocument(te.getEditorInput());
            org.eclipse.jface.text.ITextSelection sel =
                (org.eclipse.jface.text.ITextSelection) te.getSelectionProvider().getSelection();
            doc.replace(sel.getOffset(), sel.getLength(), code);
            te.getSelectionProvider().setSelection(
                new org.eclipse.jface.text.TextSelection(sel.getOffset(), code.length()));
            statusLabel.setText("Code inserted");
        } catch (Exception e) {
            statusLabel.setText("Insert failed: " + e.getMessage());
        }
    }
    
    private java.util.List<String> extractCodeBlocks(String text) {
        java.util.List<String> blocks = new java.util.ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("```(?:\\w+)?\\s*([\\s\\S]*?)```");
        Matcher m = p.matcher(text);
        while (m.find()) {
            blocks.add(m.group(1).trim());
        }
        return blocks;
    }
    
    public void dispose() {
        if (markdownRenderer != null) {
            markdownRenderer.dispose();
            markdownRenderer = null;
        }
        if (sashForm != null && !sashForm.isDisposed()) {
            sashForm.dispose();
        }
        sashForm = null;
    }
    
    public boolean isVisible() {
        return sashForm != null && !sashForm.isDisposed() && sashForm.getVisible();
    }
}
