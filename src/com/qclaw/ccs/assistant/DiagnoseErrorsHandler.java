package com.qclaw.ccs.assistant;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.ide.IDE;

import java.util.ArrayList;
import java.util.List;

public class DiagnoseErrorsHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
            if (window == null) return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return null;

            // Collect errors/warnings from active editor's resource
            IEditorPart editor = page.getActiveEditor();
            List<ErrorEntry> errors = new ArrayList<>();

            if (editor != null) {
                IResource resource = editor.getEditorInput().getAdapter(IResource.class);
                if (resource != null) {
                    IMarker[] markers = resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
                    for (IMarker marker : markers) {
                        int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                        if (severity == IMarker.SEVERITY_ERROR || severity == IMarker.SEVERITY_WARNING) {
                            errors.add(new ErrorEntry(marker));
                        }
                    }
                }
            }

            // If no errors from active editor, try all projects in workspace
            if (errors.isEmpty()) {
                // No errors in current file
                AIAssistantView view = (AIAssistantView) page.showView(AIAssistantView.ID);
                if (view != null) {
                    view.sendPromptToAI(
                        "No compilation errors or warnings found in the current editor.\n" +
                        "Please open a file with errors, or build the project first (Project > Build All)."
                    );
                }
                return null;
            }

            // Build error report
            StringBuilder report = new StringBuilder();
            report.append("编译错误诊断请求 - 以下代码中存在 ").append(errors.size()).append(" 个问题：\n\n");

            // Append source file name
            if (editor != null) {
                report.append("文件: ").append(editor.getEditorInput().getName()).append("\n\n");
            }

            for (int i = 0; i < errors.size(); i++) {
                ErrorEntry e = errors.get(i);
                report.append(e.severity).append(" #").append(i + 1);
                if (e.lineNumber > 0) report.append(" (行 ").append(e.lineNumber).append(")");
                report.append(": ").append(e.message).append("\n");
            }

            report.append("\n请逐个分析这些错误的原因，并给出修复建议和修改后的代码。");

            // Get the source code for context
            if (editor != null) {
                try {
                    org.eclipse.ui.texteditor.ITextEditor textEditor =
                        (editor instanceof org.eclipse.ui.texteditor.ITextEditor)
                            ? (org.eclipse.ui.texteditor.ITextEditor) editor
                            : editor.getAdapter(org.eclipse.ui.texteditor.ITextEditor.class);
                    if (textEditor != null) {
                        org.eclipse.ui.IEditorInput editorInput = textEditor.getEditorInput();
                        String source = textEditor.getDocumentProvider()
                            .getDocument(editorInput).get();
                        report.append("\n\n--- 源代码 ---\n").append(source);
                    }
                } catch (Exception ex) { /* source fetch failed, continue without it */ }
            }

            AIAssistantView view = (AIAssistantView) page.showView(AIAssistantView.ID);
            if (view != null) view.sendPromptToAI(report.toString());

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static class ErrorEntry {
        String severity;
        String message;
        int lineNumber;

        ErrorEntry(IMarker marker) throws CoreException {
            int sev = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            this.severity = (sev == IMarker.SEVERITY_ERROR) ? "ERROR" : "WARNING";
            this.message = marker.getAttribute(IMarker.MESSAGE, "(no message)");
            this.lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, 0);
        }
    }
}
