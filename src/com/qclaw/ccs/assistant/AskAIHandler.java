package com.qclaw.ccs.assistant;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

public class AskAIHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        try {
            IEditorPart editor = HandlerUtil.getActiveEditor(event);
            if (editor == null) return null;

            ITextEditor textEditor = adaptToTextEditor(editor);
            if (textEditor == null) return null;

            ITextSelection sel = (ITextSelection) textEditor.getSelectionProvider().getSelection();
            if (sel == null || sel.getText() == null || sel.getText().trim().isEmpty()) {
                AIAssistantView view = openAIView(event);
                if (view != null) view.sendPromptToAI("No code selected. Please select code in the editor first.");
                return null;
            }

            String selectedText = sel.getText();
            String language = detectLanguage(editor);
            String prompt = "Please analyze the following " + (language.isEmpty() ? "code" : language) + " code:\n\n" + selectedText;

            AIAssistantView view = openAIView(event);
            if (view != null) view.sendCodeToAI(selectedText, language);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private ITextEditor adaptToTextEditor(IEditorPart editor) {
        if (editor instanceof ITextEditor) return (ITextEditor) editor;
        return editor.getAdapter(ITextEditor.class);
    }

    private String detectLanguage(IEditorPart editor) {
        try {
            String name = editor.getEditorInput().getName();
            if (name == null) return "";
            if (name.endsWith(".c") || name.endsWith(".C")) return "C";
            if (name.endsWith(".h") || name.endsWith(".H")) return "C header";
            if (name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".cxx")) return "C++";
            if (name.endsWith(".hpp") || name.endsWith(".hxx")) return "C++ header";
            if (name.endsWith(".asm") || name.endsWith(".s") || name.endsWith(".S")) return "Assembly";
            if (name.endsWith(".cmd") || name.endsWith(".cfg")) return "Linker Command";
            if (name.endsWith(".syscfg")) return "TI SysConfig";
            return "";
        } catch (Exception e) { return ""; }
    }

    private AIAssistantView openAIView(ExecutionEvent event) {
        try {
            IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
            if (window == null) return null;
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return null;
            return (AIAssistantView) page.showView(AIAssistantView.ID);
        } catch (Exception e) { return null; }
    }
}
