package com.qclaw.ccs.assistant;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Settings dialog for configuring Gateway connection parameters.
 * Uses Eclipse IPreferenceStore for persistence.
 */
public class SettingsDialog extends Dialog {

    private IPreferenceStore store;
    private AIClient aiClient;
    private Text hostText;
    private Text portText;
    private Text tokenText;
    private Text modelText;

    public SettingsDialog(Shell parentShell, IPreferenceStore store, AIClient aiClient) {
        super(parentShell);
        this.store = store;
        this.aiClient = aiClient;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 10;
        layout.marginHeight = 10;
        layout.horizontalSpacing = 10;
        container.setLayout(layout);

        // Gateway Host
        new Label(container, SWT.NONE).setText("Gateway Host:");
        hostText = new Text(container, SWT.BORDER | SWT.SINGLE);
        hostText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        hostText.setMessage("127.0.0.1");

        // Gateway Port
        new Label(container, SWT.NONE).setText("Gateway Port:");
        portText = new Text(container, SWT.BORDER | SWT.SINGLE);
        portText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        portText.setMessage("50264");

        // Auth Token
        new Label(container, SWT.NONE).setText("Auth Token:");
        tokenText = new Text(container, SWT.BORDER | SWT.SINGLE);
        tokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tokenText.setMessage("Bearer token for API");

        // Model Name
        new Label(container, SWT.NONE).setText("Model Name:");
        modelText = new Text(container, SWT.BORDER | SWT.SINGLE);
        modelText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        modelText.setMessage("openclaw");

        // Load current values
        hostText.setText(store.getString("qclaw.gateway.host"));
        if (hostText.getText().isEmpty()) hostText.setText(aiClient.getGatewayHost());

        portText.setText(String.valueOf(store.getInt("qclaw.gateway.port")));
        if ("0".equals(portText.getText()) || portText.getText().isEmpty()) {
            portText.setText(String.valueOf(aiClient.getGatewayPort()));
        }

        tokenText.setText(store.getString("qclaw.gateway.token"));
        if (tokenText.getText().isEmpty()) tokenText.setText(aiClient.getAuthToken());

        modelText.setText(store.getString("qclaw.gateway.model"));
        if (modelText.getText().isEmpty()) modelText.setText(aiClient.getModel());

        return container;
    }

    @Override
    protected void okPressed() {
        // Save to preference store
        String host = hostText.getText().trim();
        String portStr = portText.getText().trim();
        String token = tokenText.getText().trim();
        String model = modelText.getText().trim();

        if (!host.isEmpty()) store.setValue("qclaw.gateway.host", host);
        if (!portStr.isEmpty()) {
            try {
                store.setValue("qclaw.gateway.port", Integer.parseInt(portStr));
            } catch (NumberFormatException e) {
                // Keep existing value
            }
        }
        if (!token.isEmpty()) store.setValue("qclaw.gateway.token", token);
        if (!model.isEmpty()) store.setValue("qclaw.gateway.model", model);

        super.okPressed();
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("CCS AI Assistant - Settings");
    }
}
