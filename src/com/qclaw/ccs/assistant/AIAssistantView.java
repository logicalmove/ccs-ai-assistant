package com.qclaw.ccs.assistant;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.LineStyleEvent;
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
import java.util.ArrayList;
import java.util.List;
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
    private Button insertCodeButton;
    private Button settingsButton;
    private Label statusLabel;
    private AIClient aiClient;
    private boolean isProcessing = false;
    private Thread activeStreamThread;
    private int totalTokensUsed = 0;
    private int lastPromptTokens = 0;
    private int lastCompletionTokens = 0;
    private StringBuilder currentResponse = new StringBuilder();
    private MarkdownRenderer markdownRenderer;

    /** Slash commands: key -> description */
    private static final String[][] SLASH_COMMANDS = {
        {"/explain",     "解释当前选中的代码"},
        {"/optimize",    "优化当前选中的代码"},
        {"/fix",         "修复当前文件中的编译错误"},
        {"/generate",    "根据描述生成代码"},
        {"/review",      "代码审查：查找潜在问题和改进建议"},
        {"/docs",        "为选中的代码生成文档注释"},
        {"/convert",     "转换代码（如 C 转 C++）"},
        {"/help",        "显示所有可用命令"},
        {"/template",    "通用模板生成：/template <描述>"},
        {"/gpio",        "GPIO 初始化代码（/gpio <描述或引脚>）"},
        {"/pwm",         "PWM 配置代码（/pwm <描述>）"},
        {"/adc",         "ADC 配置代码（/adc <描述>）"},
        {"/uart",        "UART 串口配置代码（/uart <描述>）"},
        {"/spi",         "SPI 通信配置代码（/spi <描述>）"},
        {"/i2c",         "I2C 通信配置代码（/i2c <描述>）"},
        {"/timer",       "定时器/中断配置代码（/timer <描述>）"},
        {"/dma",         "DMA 传输配置代码（/dma <描述>）"},
        {"/watchdog",    "看门狗初始化代码（/watchdog <描述>）"},
        {"/clk",         "系统时钟/PLL 配置代码（/clk <描述>）"},
        {"/ecap",        "eCAP 捕获配置代码（/ecap <描述>）"},
        {"/epwm",        "ePWM 配置代码（/epwm <描述>）"},
        {"/flash",       "Flash 编程操作代码（/flash <描述>）"},
        {"/can",         "CAN 通信配置代码（/can <描述>）"},
        {"/reg",         "寄存器查询（/reg <寄存器名>，查位域、地址、典型值）"},
        {"/refactor",    "代码重构建议（/refactor，自动分析并给出重构方案）"},
        {"/watch",       "Watch变量解释（/watch <变量名=值>，解释寄存器含义）"},
        {"/errno",       "编译器错误码解读（/errno <错误号>，如 #10099-D）"},
        {"/linker",      "链接器脚本助手（/linker <需求>，生成/修改 .cmd）"},
        {"/project",     "项目分析报告（/project，分析当前项目结构）"},
        {"/context",     "查看当前对话上下文（/context，显示对话历史摘要）"},
    };

    /** C2000 peripheral template prompts (short name -> full system prompt) */
    private static final String[][] PERIPHERAL_TEMPLATES = {
        {"gpio",    "请生成 TI C2000 GPIO 初始化和操作代码。" +
                   "使用 EALLOW/EDIS 宏保护寄存器，包含 GPxMUX、GPxDIR、GPxQSEL 等。" +
                   "需包含完整头文件引用和函数封装。"},
        {"pwm",     "请生成 TI C2000 ePWM 模块配置代码。" +
                   "包含 TBPRD（周期）、CMPA/CMPB（占空比）、AQCTL（动作限定）配置。" +
                   "需包含完整头文件引用、初始化函数和占空比设置接口。"},
        {"adc",     "请生成 TI C2000 ADC 模块配置代码。" +
                   "包含 ADCSOCxCTL（触发源）、ADCCTL1/2（时序）、ADCRESULTx 读取。" +
                   "需包含完整头文件引用和初始化函数。"},
        {"uart",    "请生成 TI C2000 SCI/UART 串口配置代码。" +
                   "包含 SCICCR（字符格式）、SCILBAUD（波特率）、SCICTL1/2（使能）配置。" +
                   "需包含完整头文件引用、初始化函数和收发接口函数。"},
        {"spi",     "请生成 TI C2000 SPI 外设配置代码。" +
                   "包含 SPICCR（字符长度/极性）、SPICTL（主从模式）、SPIBRR（波特率）配置。" +
                   "需包含完整头文件引用、初始化函数和收发接口函数。"},
        {"i2c",     "请生成 TI C2000 I2C 外设配置代码。" +
                   "包含 I2CMDR（主从模式）、I2CCLKL/H（时钟）、I2CSAR（从机地址）配置。" +
                   "需包含完整头文件引用、初始化函数和收发接口函数。"},
        {"timer",   "请生成 TI C2000 CPU Timer 或 ePWM 时间基准配置代码。" +
                   "包含 TCR（控制寄存器）、TPR（预分频）、TIMH:TIM（计数值）配置。" +
                   "需包含完整头文件引用、初始化函数和中断服务程序。"},
        {"dma",     "请生成 TI C2000 DMA 模块配置代码。" +
                   "包含 DMAADDR（源地址）、DMADST（目的地址）、DMACHX（传输计数）配置。" +
                   "需包含完整头文件引用、初始化函数和启动/停止接口。"},
        {"watchdog", "请生成 TI C2000 看门狗（WDOG）初始化和服务代码。" +
                   "包含 WDCR（看门狗控制）、WDDKEY（密钥）、WDPRES（预分频）配置。" +
                   "需包含完整头文件引用、初始化函数和服务狗函数。"},
        {"clk",     "请生成 TI C2000 系统时钟和 PLL 配置代码。" +
                   "包含 PLLCR（PLL 倍频）、PLLDIV（分频）、HISPCP/LOSPCP（高低速外设时钟）配置。" +
                   "需包含完整头文件引用、时钟初始化函数和频率计算注释。"},
        {"ecap",    "请生成 TI C2000 eCAP 模块配置代码。" +
                   "包含 ECCTL1/2（控制寄存器）、CAP1-4（捕获值）、TSCTR（时基）配置。" +
                   "需包含完整头文件引用、初始化函数和中断服务程序。"},
        {"epwm",    "请生成 TI C2000 ePWM 模块完整配置代码（与/pwm 类似但更详细）。" +
                   "包含时基(TB)、计数比较(CC)、动作限定(AQ)、死区(DB)、斩波(TZ)全部子模块配置。" +
                   "需包含完整头文件引用和配置结构体。"},
        {"flash",   "请生成 TI C2000 Flash API 编程操作代码。" +
                   "包含 Flash 编程流程（擦除/编程/验证）、Fapi_SetActiveFlashBank、Flash 等待状态配置。" +
                   "需包含完整头文件引用和操作函数。"},
        {"can",     "请生成 TI C2000 CAN 通信模块配置代码。" +
                   "包含 CANCTL（使能）、CANBTR（波特率）、CANMIM（中断掩码）、邮箱配置。" +
                   "需包含完整头文件引用、初始化函数和收发接口函数。"},
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
                        if (usage != null && !usage.isEmpty()) {
                            parseUsage(usage);
                        }
                        // Add inline Insert links for code blocks
                        addCodeBlockInsertLinks();
                    }
                    reEnableInput();
                    refreshMarkdown();
                });
            }

            @Override
            public void onError(String error) {
                outputArea.getDisplay().asyncExec(() -> {
                    if (!outputArea.isDisposed()) {
                        appendText("\nError: " + error + "\n");
                    }
                    reEnableInput();
                    refreshMarkdown();
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
            if (tm.find()) {
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
        updateMarkdownRendering();
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

    /** Insert code from last AI response into active editor */
    public void insertCodeToEditor() {
        String response = currentResponse.toString();
        List<String> codeBlocks = extractCodeBlocks(response);

        if (codeBlocks.isEmpty()) {
            updateStatus("No code blocks found in last response");
            return;
        }

        String codeToInsert;
        if (codeBlocks.size() == 1) {
            codeToInsert = codeBlocks.get(0);
        } else {
            // Multiple code blocks: show selection dialog
            codeToInsert = showCodeBlockSelector(codeBlocks);
            if (codeToInsert == null) return;
        }

        // Insert into active editor
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) { updateStatus("No active window"); return; }
            org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
            if (page == null) { updateStatus("No active page"); return; }
            org.eclipse.ui.IEditorPart editor = page.getActiveEditor();
            if (editor == null) { updateStatus("No active editor"); return; }

            org.eclipse.ui.texteditor.ITextEditor te =
                (editor instanceof org.eclipse.ui.texteditor.ITextEditor)
                    ? (org.eclipse.ui.texteditor.ITextEditor) editor
                    : editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
            if (te == null) { updateStatus("Editor does not support text insertion"); return; }

            org.eclipse.ui.IEditorInput editorInput = te.getEditorInput();
            org.eclipse.jface.text.IDocument doc = te.getDocumentProvider().getDocument(editorInput);
            org.eclipse.jface.text.ITextSelection sel =
                (org.eclipse.jface.text.ITextSelection) te.getSelectionProvider().getSelection();

            int offset = sel.getOffset();
            int length = sel.getLength();

            doc.replace(offset, length, codeToInsert);
            te.getSelectionProvider().setSelection(
                new org.eclipse.jface.text.TextSelection(offset, codeToInsert.length()));
            updateStatus("Code inserted (" + codeToInsert.length() + " chars)");
        } catch (Exception e) {
            updateStatus("Insert failed: " + e.getMessage());
        }
    }

    /** Extract code blocks from text (between ``` markers) */
    private List<String> extractCodeBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        Pattern p = Pattern.compile("```[\\w]*\\n([\\s\\S]*?)```", Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return blocks;
    }

    /** Show dialog to select one of multiple code blocks */
    private String showCodeBlockSelector(List<String> codeBlocks) {
        String[] items = new String[codeBlocks.size()];
        for (int i = 0; i < codeBlocks.size(); i++) {
            String block = codeBlocks.get(i);
            String firstLine = block.split("\\r?\\n")[0].trim();
            items[i] = "Block " + (i + 1) + ": " +
                (firstLine.length() > 50 ? firstLine.substring(0, 50) + "..." : firstLine);
        }

        org.eclipse.swt.widgets.List dialogList = new org.eclipse.swt.widgets.List(
            getSite().getShell(), SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
        for (String item : items) dialogList.add(item);

        // Simple selection dialog
        org.eclipse.swt.widgets.Shell shell = new org.eclipse.swt.widgets.Shell(
            getSite().getShell(), SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText("Select Code Block");
        shell.setSize(400, Math.min(300, 60 + codeBlocks.size() * 25));
        shell.setLayout(new GridLayout(1, false));

        org.eclipse.swt.widgets.Label label = new org.eclipse.swt.widgets.Label(shell, SWT.NONE);
        label.setText("Found " + codeBlocks.size() + " code blocks. Select one to insert:");
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        org.eclipse.swt.widgets.List list = new org.eclipse.swt.widgets.List(
            shell, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
        list.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        for (String item : items) list.add(item);
        list.setSelection(0);

        Composite btnComp = new Composite(shell, SWT.NONE);
        btnComp.setLayout(new GridLayout(2, true));
        btnComp.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        final int[] result = {0};
        final boolean[] cancelled = {false};

        Button okBtn = new Button(btnComp, SWT.PUSH);
        okBtn.setText("Insert");
        okBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        okBtn.addListener(SWT.Selection, e -> {
            result[0] = list.getSelectionIndex();
            shell.close();
        });

        Button cancelBtn = new Button(btnComp, SWT.PUSH);
        cancelBtn.setText("Cancel");
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> {
            cancelled[0] = true;
            shell.close();
        });

        shell.open();
        org.eclipse.swt.widgets.Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) display.sleep();
        }

        if (cancelled[0]) return null;
        int idx = result[0];
        return (idx >= 0 && idx < codeBlocks.size()) ? codeBlocks.get(idx) : null;
    }

    /** Open settings dialog */
    public void openSettings() {
        org.eclipse.jface.preference.IPreferenceStore store =
            org.eclipse.ui.PlatformUI.getWorkbench().getPreferenceStore();

        // Set defaults if not set
        store.setDefault("qclaw.gateway.host", "127.0.0.1");
        store.setDefault("qclaw.gateway.port", "50264");
        store.setDefault("qclaw.gateway.token", aiClient.getAuthToken());
        store.setDefault("qclaw.gateway.model", "openclaw");

        SettingsDialog dialog = new SettingsDialog(getSite().getShell(), store, aiClient);
        dialog.open();

        // Apply settings
        String host = store.getString("qclaw.gateway.host");
        int port = store.getInt("qclaw.gateway.port");
        String token = store.getString("qclaw.gateway.token");
        String model = store.getString("qclaw.gateway.model");
        aiClient.setConfig(host, port, token, model);
        updateStatus("Ready [" + aiClient.getConfigString() + "]");
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
            String tokenInfo = "";
            if (totalTokensUsed > 0 || lastPromptTokens > 0) {
                tokenInfo = " | Tokens: " + totalTokensUsed;
                if (lastPromptTokens > 0 || lastCompletionTokens > 0) {
                    tokenInfo += " (+" + lastPromptTokens + " prompt, " + lastCompletionTokens + " completion)";
                }
            }
            String configInfo = " [" + aiClient.getConfigString() + "]";
            statusLabel.setText(text + tokenInfo + configInfo);
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 5;
        layout.marginHeight = 5;
        parent.setLayout(layout);

        aiClient = new AIClient();

        // Load saved settings
        org.eclipse.jface.preference.IPreferenceStore store =
            org.eclipse.ui.PlatformUI.getWorkbench().getPreferenceStore();
        String host = store.getString("qclaw.gateway.host");
        int port = store.getInt("qclaw.gateway.port");
        String token = store.getString("qclaw.gateway.token");
        String model = store.getString("qclaw.gateway.model");
        aiClient.setConfig(host, port, token, model);

        // Set up history persistence
        try {
            java.net.URL dataUrl = org.eclipse.core.runtime.Platform.getInstanceLocation().getURL();
            java.io.File historyDir = new java.io.File(dataUrl.getPath());
            historyDir.mkdirs();
            aiClient.setHistoryFilePath(historyDir.getAbsolutePath() + java.io.File.separator + "qclaw-chat-history.json");
        } catch (Exception e) { /* history not available */ }

        // Output area
        outputArea = new StyledText(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP);
        outputArea.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        outputArea.setEditable(false);
        // Apply markdown rendering
        applyMarkdownRendering();

        // Mouse click handler for insert block links
        outputArea.addMouseListener(new org.eclipse.swt.events.MouseListener() {
            public void mouseDoubleClick(org.eclipse.swt.events.MouseEvent e) {}
            public void mouseDown(org.eclipse.swt.events.MouseEvent e) {}
            public void mouseUp(org.eclipse.swt.events.MouseEvent e) {
                try {
                    int offset = outputArea.getOffsetAtLocation(new org.eclipse.swt.graphics.Point(e.x, e.y));
                    org.eclipse.swt.custom.StyleRange style = outputArea.getStyleRangeAtOffset(offset);
                    if (style != null && style.underline && style.data instanceof Integer) {
                        handleInsertLinkClick(offset);
                    }
                } catch (Exception ex) { /* not on text */ }
            }
        });

        // Button bar
        Composite buttonComposite = new Composite(parent, SWT.NONE);
        GridLayout btnLayout = new GridLayout(8, false);
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

        insertCodeButton = new Button(buttonComposite, SWT.PUSH);
        insertCodeButton.setText("Insert");
        insertCodeButton.setToolTipText("Insert code from last AI response into editor");
        insertCodeButton.addListener(SWT.Selection, e -> insertCodeToEditor());

        exportButton = new Button(buttonComposite, SWT.PUSH);
        exportButton.setText("Export");
        exportButton.addListener(SWT.Selection, e -> exportChat());

        clearButton = new Button(buttonComposite, SWT.PUSH);
        clearButton.setText("Clear");
        clearButton.addListener(SWT.Selection, e -> clearChat());

        settingsButton = new Button(buttonComposite, SWT.PUSH);
        settingsButton.setText("Settings");
        settingsButton.setToolTipText("Configure Gateway settings");
        settingsButton.addListener(SWT.Selection, e -> openSettings());

        // Handle Enter key
        inputField.addListener(SWT.DefaultSelection, e -> sendMessage());

        // Status bar
        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Load chat history
        boolean loaded = aiClient.loadHistory();
        if (loaded) {
            String history = aiClient.getRecentHistory(20);
            outputArea.setText(buildWelcomeMessage() + "\n--- Restored History ---\n\n" + history);
            outputArea.setSelection(outputArea.getText().length());
        } else {
            outputArea.setText(buildWelcomeMessage());
        }
        updateStatus("Ready");
        inputField.setFocus();
    }

    private String buildWelcomeMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CCS AI Assistant ===\n\n");
        sb.append("Welcome to CCS AI Assistant!\n\n");
        sb.append("Quick Actions:\n");
        sb.append("  - Select code, right-click > QClaw > Ask AI\n");
        sb.append("  - Select code, press Ctrl+1 to analyze\n");
        sb.append("  - Right-click > QClaw > Diagnose Errors\n");
        sb.append("  - Ctrl+2 to diagnose compilation errors\n");
        sb.append("  - Insert button to paste AI code into editor\n\n");
        sb.append("Slash Commands:\n");
        for (String[] cmd : SLASH_COMMANDS) {
            sb.append(String.format("  %-12s %s\n", cmd[0], cmd[1]));
        }
        sb.append("\nSettings > Configure Gateway address and model\n\n");
        return sb.toString();
    }

    /** Handle slash commands */
    private boolean handleSlashCommand(String input) {
        if (!input.startsWith("/")) return false;

        String cmd = input.trim().toLowerCase();
        String args = "";

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
            case "/template":
                handleTemplateCommand(args);
                return true;
            case "/gpio":
            case "/pwm":
            case "/epwm":
            case "/adc":
            case "/uart":
            case "/spi":
            case "/i2c":
            case "/timer":
            case "/dma":
            case "/watchdog":
            case "/clk":
            case "/ecap":
            case "/flash":
            case "/can":
                handlePeripheralTemplate(cmd.substring(1), args);
                return true;
            case "/reg":
                handleRegisterQuery(args);
                return true;
            case "/refactor":
                sendWithEditorContext("请对以下代码进行重构分析和建议。具体包括：\n\n"
                    + "1. 消除魔法数字，用有意义的宏或常量替代\n"
                    + "2. 提取重复代码为函数\n"
                    + "3. 优化函数长度和复杂度\n"
                    + "4. 改善变量命名\n"
                    + "5. 消除不必要的全局变量\n"
                    + "6. 优化控制流（减少嵌套）\n"
                    + "7. 直接给出重构后的完整代码，不要只给建议\n\n"
                    + "代码如下：\n\n", args);
                return true;
            case "/watch":
                handleWatchQuery(args);
                return true;
            case "/errno":
                handleErrorCodeQuery(args);
                return true;
            case "/linker":
                handleLinkerCommand(args);
                return true;
            case "/project":
                handleProjectAnalysis(args);
                return true;
            case "/context":
                showContextInfo();
                return true;
            default:
                appendMessage("System: Unknown command: " + cmd + "\nType /help for available commands.");
                return true;
        }
    }

    /** Handle /template <description> - general template generation */
    private void handleTemplateCommand(String description) {
        if (description.isEmpty()) {
            appendMessage("System: Usage: /template <description>\n"
                + "Example: /template F28335 LED toggle with 1 second delay\n"
                + "Example: /template SCI receive interrupt handler\n"
                + "Quick templates: /gpio /pwm /adc /uart /spi /i2c /timer /dma /watchdog /clk /ecap /epwm /flash /can");
            return;
        }
        String prompt = "你是一个 TI C2000 嵌入式开发专家。请根据以下描述生成完整的 C 代码模板，"
            + "包括：\n"
            + "1. 必要的头文件（#include Device.h 等）\n"
            + "2. 宏定义和常量\n"
            + "3. 初始化函数\n"
            + "4. 主函数调用示例\n"
            + "5. 关键寄存器配置注释（说明每个寄存器的作用和取值原因）\n"
            + "6. 使用 EALLOW/EDIS 保护受保护寄存器\n\n"
            + "请用中文注释解释关键步骤。\n\n"
            + "需求描述：" + description;
        sendPromptToAI(prompt);
    }

    /** Handle peripheral-specific quick commands (/gpio, /pwm, etc.) */
    private void handlePeripheralTemplate(String peripheral, String args) {
        String systemPrompt = null;
        String peripheralName = peripheral.toUpperCase();
        for (String[] tmpl : PERIPHERAL_TEMPLATES) {
            if (tmpl[0].equalsIgnoreCase(peripheral)) {
                systemPrompt = tmpl[1];
                // Friendly name for display
                if ("epwm".equals(peripheral)) peripheralName = "ePWM";
                else if ("ecap".equals(peripheral)) peripheralName = "eCAP";
                else if ("adc".equals(peripheral)) peripheralName = "ADC";
                else if ("dma".equals(peripheral)) peripheralName = "DMA";
                else if ("clk".equals(peripheral)) peripheralName = "Clock/PLL";
                else if ("spi".equals(peripheral)) peripheralName = "SPI";
                else if ("i2c".equals(peripheral)) peripheralName = "I2C";
                else if ("can".equals(peripheral)) peripheralName = "CAN";
                else peripheralName = peripheral.toUpperCase();
                break;
            }
        }
        if (systemPrompt == null) systemPrompt = "请生成 TI C2000 " + peripheralName + " 模块配置代码。";

        String additional = "";
        if (!args.isEmpty()) {
            additional = "\n\n用户附加要求：" + args;
        }

        // Also include selected code if any
        String selectedCode = getActiveEditorSelection();
        String codeContext = "";
        if (!selectedCode.isEmpty() && selectedCode.length() < 3000) {
            codeContext = "\n\n用户当前选中的代码（作为参考上下文）：\n```c\n" + selectedCode + "\n```";
        }

        String prompt = systemPrompt
            + "\n\n要求："
            + "1. 包含 Device.h 和所有必要头文件"
            + "\n2. 使用 EALLOW/EDIS 宏保护配置寄存器"
            + "\n3. 封装为清晰的初始化函数和操作接口"
            + "\n4. 用中文注释每个寄存器设置的含义"
            + "\n5. 提供主函数中的调用示例"
            + "\n6. 芯片型号默认 F28335，如有特殊要求请说明"
            + additional
            + codeContext;

        sendPromptToAI(prompt);
    }

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
        sb.append("  - Insert button pastes AI code blocks into your editor\n");
        appendMessage("System: " + sb.toString());
    }

    /** Show current conversation context info */
    private void showContextInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 对话上下文 ===\n\n");

        // Show config
        sb.append("Gateway: ").append(aiClient.getConfigString()).append("\n");
        sb.append("Token 用量: ").append(totalTokensUsed).append("\n\n");

        // Show message count
        int msgCount = aiClient.getMessageCount();
        sb.append("当前对话消息数: ").append(msgCount).append("\n");

        // Show history file status
        String historyPath = aiClient.getHistoryFilePath();
        if (historyPath != null) {
            sb.append("历史文件: ").append(historyPath).append("\n");
            java.io.File hf = new java.io.File(historyPath);
            if (hf.exists()) {
                sb.append("历史文件大小: ").append(hf.length()).append(" bytes\n");
            } else {
                sb.append("历史文件: (未创建)\n");
            }
        }

        // Show editor context
        String editorFile = getActiveEditorFileName();
        if (!editorFile.isEmpty()) {
            sb.append("\n当前编辑器: ").append(editorFile).append("\n");
        } else {
            sb.append("\n当前编辑器: (无)\n");
        }

        // Recent conversation summary (last 5 messages)
        sb.append("\n--- 最近对话 ---\n");
        String recent = aiClient.getRecentHistory(5);
        if (recent.isEmpty()) {
            sb.append("(无历史)\n");
        } else {
            // Truncate if too long
            if (recent.length() > 1000) {
                sb.append(recent.substring(0, 1000)).append("\n...(truncated)\n");
            } else {
                sb.append(recent);
            }
        }

        sb.append("\n提示:");
        sb.append("  /clear - 清空对话开始新会话\n");
        sb.append("  /export - 导出对话到文件\n");
        sb.append("  对话会自动保存到历史文件\n");

        appendMessage("System: " + sb.toString());
    }

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

    /** Handle /reg <register_name> - register query */
    private void handleRegisterQuery(String regName) {
        // If no arg, try to get selected text from editor
        if (regName.isEmpty()) {
            String selected = getActiveEditorSelection();
            if (!selected.isEmpty()) {
                // Extract first word that looks like a register name
                String[] words = selected.trim().split("[\\s,;=()]+");
                for (String w : words) {
                    if (w.length() >= 3 && w.matches("[A-Za-z][A-Za-z0-9_]*")) {
                        regName = w;
                        break;
                    }
                }
            }
        }
        if (regName.isEmpty()) {
            appendMessage("System: Usage: /reg <寄存器名>\n"
                + "Example: /reg GPIOA_MUX1\n"
                + "Example: /reg EPWM1_TBCTL\n"
                + "Example: /reg ADCSOC0CTL\n"
                + "Tip: Select code containing register name, then type /reg without args.");
            return;
        }

        String prompt = "请查询 TI C2000 (默认 F28335) 的寄存器 " + regName + " 的详细信息，包括：\n\n"
            + "1. 寄存器全名和缩写\n"
            + "2. 寄存器地址（十六进制）\n"
            + "3. 位域分布表（每一位/字段的功能说明）\n"
            + "4. 访问类型（读写/只读/写1清零等）\n"
            + "5. 复位默认值\n"
            + "6. 常用配置示例（附中文注释）\n"
            + "7. 是否需要 EALLOW/EDIS 保护\n"
            + "8. 相关寄存器/寄存器组\n\n"
            + "请用表格或结构化格式输出，中文说明。";
        sendPromptToAI(prompt);
    }

    /** Handle /watch <var=value> - explain debug watch variable values */
    private void handleWatchQuery(String input) {
        if (input.isEmpty()) {
            appendMessage("System: Usage: /watch <变量名=值>\n"
                + "Example: /watch GpioCtrlRegs.GPAMUX1.all=0x0000\n"
                + "Example: /watch EPwm1Regs.TBCTL.all=0x0003\n"
                + "Example: /watch ADCRESULT0=2048\n"
                + "Tip: Paste multiple vars: /watch TBCTL=0x0003, TBPRIOD=0x1FFF\n"
                + "Supports: register names, hex values, decimal values");
            return;
        }

        String prompt = "请解释以下 TI C2000 (F28335) CCS Debug Watch 窗口中的变量/寄存器值：\n\n"
            + "Watch 数据：" + input + "\n\n"
            + "请对每个变量进行：\n"
            + "1. 识别寄存器/变量类型\n"
            + "2. 逐位解析十六进制值的含义（哪些位被设置/清除）\n"
            + "3. 解释该值代表的功能状态\n"
            + "4. 指出是否有异常或不常用的设置\n"
            + "5. 如有相关标志位，说明中断/状态含义（如 EALLOW、INT、PIE 等）\n\n"
            + "用中文解释，格式清晰。";
        sendPromptToAI(prompt);
    }

    /** Handle /errno <error_code> - explain compiler error codes */
    private void handleErrorCodeQuery(String errorCode) {
        if (errorCode.isEmpty()) {
            appendMessage("System: Usage: /errno <错误号>\n"
                + "Example: /errno #10099-D\n"
                + "Example: /errno #123\n"
                + "Tip: Paste full error line: /errno expected a \"}\" to close brace\n"
                + "Common TI C2000 compiler errors: \n"
                + "  #10099-D  未声明标识符\n"
                + "  #20      语法错误\n"
                + "  #146     未定义符号\n"
                + "  #64      不可达代码\n"
                + "  #142     未引用变量\n");
            return;
        }

        // Try to extract error number from text
        String errorNum = errorCode;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#(\\d+)(-[A-Z])?").matcher(errorCode);
        if (m.find()) {
            errorNum = m.group(0);
        }

        String prompt = "请解释以下 TI C2000 编译器（TI ARM/C2000 CGT）错误码的详细信息：\n\n"
            + "错误码：" + errorCode + "\n\n"
            + "请提供：\n"
            + "1. 错误的完整描述（中英文）\n"
            + "2. 错误的严重等级（Fatal/Error/Warning/Remark）\n"
            + "3. 常见触发场景和原因\n"
            + "4. 具体修复步骤（附代码示例）\n"
            + "5. 预防此错误的建议\n\n"
            + "如果是警告，说明是否可以安全忽略。\n"
            + "用中文回答。";
        sendPromptToAI(prompt);
    }

    /** Handle /linker <requirement> - linker script (.cmd) assistant */
    private void handleLinkerCommand(String requirement) {
        if (requirement.isEmpty()) {
            appendMessage("System: Usage: /linker <需求>\n"
                + "Example: /linker add a new section .mydata at FLASHB, size 0x100\n"
                + "Example: /linker explain current .cmd file\n"
                + "Example: /linker allocate DMA buffer in RAMGS0\n"
                + "Example: /linker F28335 full linker script template\n"
                + "Tip: Select existing .cmd content first, then type /linker explain");
            return;
        }

        String selectedCode = getActiveEditorSelection();
        String cmdContext = "";
        if (!selectedCode.isEmpty() && selectedCode.length() < 5000) {
            cmdContext = "\n\n当前编辑器中的 .cmd 文件内容：\n```linker\n" + selectedCode + "\n```";
        }

        String prompt = "请作为 TI C2000 链接器脚本(.cmd)专家，处理以下需求：\n\n"
            + "需求：" + requirement + cmdContext + "\n\n"
            + "要求：\n"
            + "1. 如需生成模板，提供 F28335 完整的 MEMORY 和 SECTIONS 配置\n"
            + "2. 包含常用 sections：.text, .cinit, .pinit, .econst, .switch, .bss, .ebss, .sysmem\n"
            + "3. 注释每个 MEMORY 页和区域的地址范围、用途\n"
            + "4. 如需修改，给出具体修改前后的对比\n"
            + "5. 说明自定义 section 的正确放置方式\n"
            + "6. 用中文注释，给出完整可直接使用的 .cmd 文件内容\n"
            + "7. 如涉及 C2000 特有概念（PIE, ramfuncs 等），做出说明";
        sendPromptToAI(prompt);
    }

    /** Handle /project - analyze current project structure */
    private void handleProjectAnalysis(String options) {
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) { appendMessage("System: No active workbench window."); return; }
            org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
            if (page == null) { appendMessage("System: No active page."); return; }
            org.eclipse.ui.IEditorPart editor = page.getActiveEditor();
            if (editor == null) { appendMessage("System: No editor open. Open a file in your project first."); return; }

            org.eclipse.core.resources.IResource resource =
                editor.getEditorInput().getAdapter(org.eclipse.core.resources.IResource.class);
            if (resource == null) { appendMessage("System: Cannot access project resource."); return; }

            org.eclipse.core.resources.IProject project = resource.getProject();
            if (project == null) { appendMessage("System: File is not in a project."); return; }

            // Collect project info
            StringBuilder report = new StringBuilder();
            report.append("项目分析报告\n");
            report.append("============\n\n");

            // Project name and description
            report.append("项目名称: ").append(project.getName()).append("\n");
            try { report.append("项目路径: ").append(project.getLocation().toOSString()).append("\n"); } catch (Exception e) {}
            report.append("是否打开: ").append(project.isOpen() ? "是" : "否").append("\n\n");

            // File structure summary
            report.append("--- 文件统计 ---\n");
            java.util.Map<String, Integer> extCount = new java.util.TreeMap<>();
            java.util.List<String> sourceFiles = new java.util.ArrayList<>();
            int totalFiles = 0;
            try {
                org.eclipse.core.resources.IResource[] members = project.members();
                for (org.eclipse.core.resources.IResource member : members) {
                    collectFileStats(member, extCount, sourceFiles, 0);
                }
                for (java.util.Map.Entry<String, Integer> entry : extCount.entrySet()) {
                    report.append("  .").append(entry.getKey()).append(" : ").append(entry.getValue()).append(" 个文件\n");
                    totalFiles += entry.getValue();
                }
                report.append("  总计: ").append(totalFiles).append(" 个文件\n");
            } catch (Exception e) {
                report.append("  (无法读取文件结构: ").append(e.getMessage()).append(")\n");
            }

            // Source file list (top 20)
            report.append("\n--- 源文件列表 ---\n");
            int count = 0;
            for (String f : sourceFiles) {
                if (count++ >= 20) { report.append("  ... (and more)\n"); break; }
                report.append("  ").append(f).append("\n");
            }

            // Include paths and build settings
            report.append("\n--- 构建配置 ---\n");
            try {
                org.eclipse.core.resources.IFile cproject = project.getFile(".cproject");
                if (cproject.exists()) {
                    String content = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(cproject.getLocation().toOSString())));
                    // Extract include paths
                    java.util.List<String> includePaths = extractXmlValues(content, "include");
                    if (!includePaths.isEmpty()) {
                        report.append("  Include 路径:\n");
                        for (String ip : includePaths) {
                            report.append("    ").append(ip).append("\n");
                        }
                    }
                }
            } catch (Exception e) {
                report.append("  (构建配置需在 CCS 中查看)\n");
            }

            // Linker script detection
            report.append("\n--- 链接器脚本 ---\n");
            boolean foundCmd = false;
            for (String f : sourceFiles) {
                if (f.endsWith(".cmd")) {
                    report.append("  ").append(f).append("\n");
                    foundCmd = true;
                }
            }
            if (!foundCmd) report.append("  (未找到 .cmd 文件)\n");

            report.append("\n请根据以上项目结构给出：");
            report.append("1. 项目整体评价和建议\n");
            report.append("2. 文件组织是否合理\n");
            report.append("3. 是否缺少关键配置文件\n");
            report.append("4. 常见 C2000 项目最佳实践建议\n");

            appendMessage("System: " + report.toString());
            sendPromptToAI(report.toString());

        } catch (Exception e) {
            appendMessage("System: 项目分析失败: " + e.getMessage());
        }
    }

    /** Recursively collect file statistics */
    private void collectFileStats(org.eclipse.core.resources.IResource resource,
            java.util.Map<String, Integer> extCount, java.util.List<String> sourceFiles, int depth) {
        if (depth > 5) return;
        try {
            if (resource.getType() == org.eclipse.core.resources.IResource.FILE) {
                String name = resource.getName();
                int dotIdx = name.lastIndexOf('.');
                if (dotIdx > 0) {
                    String ext = name.substring(dotIdx + 1);
                    extCount.merge(ext, 1, Integer::sum);
                }
                String lower = name.toLowerCase();
                if (lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h")
                    || lower.endsWith(".asm") || lower.endsWith(".cmd") || lower.endsWith(".syscfg")) {
                    sourceFiles.add(name);
                }
            } else if (resource.getType() == org.eclipse.core.resources.IResource.FOLDER) {
                org.eclipse.core.resources.IResource[] members = ((org.eclipse.core.resources.IFolder) resource).members();
                for (org.eclipse.core.resources.IResource member : members) {
                    collectFileStats(member, extCount, sourceFiles, depth + 1);
                }
            }
        } catch (Exception e) { /* skip */ }
    }

    /** Simple XML value extractor */
    private java.util.List<String> extractXmlValues(String xml, String tag) {
        java.util.List<String> values = new java.util.ArrayList<>();
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("<" + tag + "[^>]*>([^<]+)</" + tag + ">", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(xml);
            while (m.find()) values.add(m.group(1).trim());
        } catch (Exception e) { /* skip */ }
        return values;
    }

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

            org.eclipse.ui.IEditorInput editorInput = te.getEditorInput();
            return te.getDocumentProvider().getDocument(editorInput).get();
        } catch (Exception e) {
            return "";
        }
    }

    private String detectEditorLanguage() {
        try {
            String name = getActiveEditorFileName();
            if (name == null || name.isEmpty()) return "c";
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

    /** Get active editor file name */
    private String getActiveEditorFileName() {
        try {
            org.eclipse.ui.IEditorPart editor =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow()
                    .getActivePage().getActiveEditor();
            if (editor == null) return "";
            String name = editor.getEditorInput().getName();
            return name != null ? name : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void sendMessage() {
        if (isProcessing) return;
        String message = inputField.getText().trim();
        if (message.isEmpty()) return;
        inputField.setText("");

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
        updateMarkdownRendering();
    }

    private void appendText(String text) {
        outputArea.append(text);
    }

    /** Batch markdown update (called after streaming completes) */
    private void refreshMarkdown() {
        updateMarkdownRendering();
    }

    /** Apply markdown rendering to output area */
    private void applyMarkdownRendering() {
        if (markdownRenderer != null) {
            markdownRenderer.dispose();
        }
        markdownRenderer = new MarkdownRenderer(outputArea.getDisplay(), outputArea.getText());
        outputArea.removeLineStyleListener(markdownRenderer);
        outputArea.addLineStyleListener(markdownRenderer);
    }

    /** Add clickable [Insert Block N] links after code blocks in last AI response */
    private void addCodeBlockInsertLinks() {
        String response = currentResponse.toString();
        List<String> codeBlocks = extractCodeBlocks(response);
        if (codeBlocks.isEmpty()) return;

        String text = outputArea.getText();
        // Find positions of code block endings (```) in the output text
        // We'll append insert links after the last AI response section
        StringBuilder linksSb = new StringBuilder();
        for (int i = 0; i < codeBlocks.size(); i++) {
            linksSb.append("\n  \u200B@INSERT_BLOCK_").append(i).append("@ \u200B");
        }
        outputArea.append(linksSb.toString());

        // Apply hyperlink style to insert markers
        String fullText = outputArea.getText();
        java.util.regex.Pattern markerPat = java.util.regex.Pattern.compile("@INSERT_BLOCK_(\\d+)@");
        java.util.regex.Matcher markerMat = markerPat.matcher(fullText);
        while (markerMat.find()) {
            int start = markerMat.start();
            int end = markerMat.end();
            int blockIdx = Integer.parseInt(markerMat.group(1));
            String label = codeBlocks.size() == 1 ? "\u25B6 Insert" : "\u25B6 Insert Block " + (blockIdx + 1);
            org.eclipse.swt.custom.StyleRange style = new org.eclipse.swt.custom.StyleRange();
            style.start = start;
            style.length = end - start;
            style.foreground = new org.eclipse.swt.graphics.Color(outputArea.getDisplay(),
                new org.eclipse.swt.graphics.RGB(0, 90, 156));
            style.underline = true;
            style.underlineStyle = org.eclipse.swt.SWT.UNDERLINE_LINK;
            style.data = blockIdx; // store block index
            outputArea.setStyleRange(style);
        }
    }

    /** Handle mouse clicks on insert block links */
    private void handleInsertLinkClick(int offset) {
        try {
            org.eclipse.swt.custom.StyleRange style = outputArea.getStyleRangeAtOffset(offset);
            if (style != null && style.data instanceof Integer) {
                int blockIdx = (Integer) style.data;
                List<String> codeBlocks = extractCodeBlocks(currentResponse.toString());
                if (blockIdx >= 0 && blockIdx < codeBlocks.size()) {
                    String codeToInsert = codeBlocks.get(blockIdx);
                    insertCodeToEditor(codeToInsert);
                }
            }
        } catch (Exception e) {
            updateStatus("Insert failed: " + e.getMessage());
        }
    }

    /** Insert a specific code string into the active editor */
    private void insertCodeToEditor(String codeToInsert) {
        try {
            org.eclipse.ui.IWorkbenchWindow window =
                org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) { updateStatus("No active window"); return; }
            org.eclipse.ui.IWorkbenchPage page = window.getActivePage();
            if (page == null) { updateStatus("No active page"); return; }
            org.eclipse.ui.IEditorPart editor = page.getActiveEditor();
            if (editor == null) { updateStatus("No active editor"); return; }

            org.eclipse.ui.texteditor.ITextEditor te =
                (editor instanceof org.eclipse.ui.texteditor.ITextEditor)
                    ? (org.eclipse.ui.texteditor.ITextEditor) editor
                    : editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
            if (te == null) { updateStatus("Editor does not support text insertion"); return; }

            org.eclipse.ui.IEditorInput editorInput = te.getEditorInput();
            org.eclipse.jface.text.IDocument doc = te.getDocumentProvider().getDocument(editorInput);
            org.eclipse.jface.text.ITextSelection sel =
                (org.eclipse.jface.text.ITextSelection) te.getSelectionProvider().getSelection();

            int off = sel.getOffset();
            int len = sel.getLength();
            doc.replace(off, len, codeToInsert);
            te.getSelectionProvider().setSelection(
                new org.eclipse.jface.text.TextSelection(off, codeToInsert.length()));
            updateStatus("Code inserted (" + codeToInsert.length() + " chars)");
        } catch (Exception e) {
            updateStatus("Insert failed: " + e.getMessage());
        }
    }

    /** Update markdown renderer after text changes */
    private void updateMarkdownRendering() {
        outputArea.getDisplay().asyncExec(() -> {
            if (!outputArea.isDisposed()) {
                applyMarkdownRendering();
            }
        });
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
