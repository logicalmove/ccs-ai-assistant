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
    private StringBuilder currentResponse = new StringBuilder();

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
            public void onComplete() {
                outputArea.getDisplay().asyncExec(() -> {
                    if (!outputArea.isDisposed()) {
                        appendText("\n");
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
        outputArea.setText("=== CCS AI Assistant ===\n\nChat cleared.\n\n");
        aiClient.clearHistory();
        totalTokensUsed = 0;
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
            String tokenInfo = totalTokensUsed > 0 ? " | Tokens: " + totalTokensUsed : "";
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
        outputArea.setText("=== CCS AI Assistant ===\n\n" +
            "Welcome to CCS AI Assistant!\n" +
            "Type your question below and press Enter or click Send.\n\n" +
            "Tips:\n" +
            "- Select code, right-click > Ask AI\n" +
            "- Select code, press Ctrl+1 to ask AI\n" +
            "- Click Stop to interrupt generation\n" +
            "- Click Copy to copy last AI response\n" +
            "- Click Export to save chat history\n\n");
        outputArea.setEditable(false);

        // Button bar - 6 columns
        Composite buttonComposite = new Composite(parent, SWT.NONE);
        GridLayout btnLayout = new GridLayout(6, false);
        btnLayout.marginWidth = 0;
        btnLayout.marginHeight = 5;
        buttonComposite.setLayout(btnLayout);
        buttonComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        inputField = new Text(buttonComposite, SWT.BORDER | SWT.SINGLE);
        inputField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        inputField.setMessage("Type your question...");

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

        inputField.addListener(SWT.DefaultSelection, e -> sendMessage());

        // Status bar
        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusLabel.setText("Ready");
    }

    private void sendMessage() {
        if (isProcessing) return;
        String message = inputField.getText().trim();
        if (message.isEmpty()) return;
        inputField.setText("");
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

    @Override
    public void setFocus() {
        if (inputField != null && !inputField.isDisposed()) {
            inputField.setFocus();
        }
    }
}
