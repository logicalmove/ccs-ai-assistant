package com.qclaw.ccs.assistant;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.*;
import org.eclipse.ui.console.*;
import org.eclipse.ui.*;
import org.eclipse.ui.part.*;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.jface.text.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/**
 * CCS Project Automation: import, build, auto-fix, and workflow orchestration.
 * Operates on the CCS workspace at C:\ti\workspace
 */
public class CCSProjectManager {
    private static final String WORKSPACE_ROOT = "C:\\ti\\workspace";
    private static final String CCS_NATURE_ID = "com.ti.ccstudio.project.CCSProjectNature";
    private static final String BUILD_CONFIG_ATTR = "com.ti.ccstudio.project.buildConfigAttr";
    
    private final MessageConsole console;
    private MessageConsoleStream consoleStream;
    private final AIClient aiClient;
    private volatile boolean buildInProgress = false;
    private final AtomicInteger fixAttemptCount = new AtomicInteger(0);
    private static final int MAX_FIX_ATTEMPTS = 3;
    
    public CCSProjectManager(AIClient aiClient) {
        this.aiClient = aiClient;
        this.console = findOrCreateConsole();
        this.consoleStream = console.newMessageStream();
    }
    
    private MessageConsole findOrCreateConsole() {
        IConsoleManager conMan = org.eclipse.ui.console.ConsolePlugin.getDefault().getConsoleManager();
        MessageConsole myConsole = null;
        for (IConsole c : conMan.getConsoles()) {
            if ("QClaw AI".equals(c.getName())) {
                myConsole = (MessageConsole) c;
                break;
            }
        }
        if (myConsole == null) {
            myConsole = new MessageConsole("QClaw AI", null);
            conMan.addConsoles(new IConsole[]{myConsole});
        }
        return myConsole;
    }
    
    // ============================================================
    // Public Automation Commands
    // ============================================================
    
    /**
     * Auto workflow: search -> import -> build -> AI fix -> rebuild
     * Loop up to MAX_FIX_ATTEMPTS times.
     */
    public void runAutoWorkflow(String projectPattern, IProgressMonitor monitor) {
        try {
            log("=== QClaw Auto Workflow ===");
            log("Project pattern: " + projectPattern);
            
            // Phase 1: Find projects
            log("\n[Phase 1/5] Searching for projects...");
            List<java.io.File> found = searchProjects(projectPattern, monitor);
            if (found.isEmpty()) {
                log("ERROR: No projects found matching '" + projectPattern + "'");
                return;
            }
            log("Found " + found.size() + " project(s)");
            for (java.io.File f : found) log("  -> " + f.getParent());
            
            // Phase 2: Import
            log("\n[Phase 2/5] Importing project(s)...");
            IProject imported = importProject(found.get(0), monitor);
            if (imported == null) {
                log("ERROR: Import failed");
                return;
            }
            log("Imported: " + imported.getName());
            
            // Phase 3: Build loop with AI fix
            boolean buildSuccess = buildProject(imported, monitor);
            int attempts = 0;
            
            while (!buildSuccess && fixAttemptCount.incrementAndGet() <= MAX_FIX_ATTEMPTS) {
                attempts++;
                log("\n[Phase 3." + attempts + "/5] Build failed - invoking AI auto-fix...");
                
                // Collect errors
                List<BuildError> errors = collectBuildErrors(imported, monitor);
                if (errors.isEmpty()) {
                    log("No errors found, but build failed - checking build output");
                    break;
                }
                log("Collected " + errors.size() + " error(s)");
                
                // Phase 4: AI auto-fix
                log("\n[Phase 4/5] AI analyzing and fixing errors...");
                boolean fixed = autoFixErrors(imported, errors, monitor);
                if (!fixed) {
                    log("AI could not auto-fix errors. Manual intervention required.");
                    break;
                }
                
                // Phase 5: Rebuild
                log("\n[Phase 5/5] Rebuilding project...");
                buildSuccess = buildProject(imported, monitor);
            }
            
            if (buildSuccess) {
                log("\n=== Auto workflow SUCCESS ===");
                log("Project built successfully after " + attempts + " AI fix attempt(s)");
            } else {
                log("\n=== Auto workflow FAILED ===");
                log("Build failed after " + MAX_FIX_ATTEMPTS + " fix attempts. Check errors above.");
            }
            
        } catch (Exception e) {
            log("Workflow error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /** Search for CCS project directories */
    public List<java.io.File> searchProjects(String pattern, IProgressMonitor monitor) {
        List<java.io.File> results = new ArrayList<>();
        Set<String> scanned = new HashSet<>();
        
        // Search in common TI locations
        String[] searchRoots = {
            "C:\\ti",
            "C:\\Users\\" + System.getProperty("user.name"),
            "C:\\workspace",
            WORKSPACE_ROOT
        };
        
        Pattern p = Pattern.compile(pattern.replace("*", ".*"), Pattern.CASE_INSENSITIVE);
        
        for (String root : searchRoots) {
            if (monitor != null && monitor.isCanceled()) break;
            File rootDir = new File(root);
            if (!rootDir.exists()) continue;
            
            scanForProjects(rootDir, p, results, scanned, 0, 6);
        }
        
        // Also check workspace
        File wsDir = new File(WORKSPACE_ROOT);
        if (wsDir.exists()) {
            for (File proj : wsDir.listFiles()) {
                if (proj.isDirectory() && !scanned.contains(proj.getAbsolutePath())) {
                    if (proj.getName().toLowerCase().contains(pattern.toLowerCase().replace("*", ""))
                        || pattern.equals("*")) {
                        results.add(new File(proj, ".project"));
                    }
                }
            }
        }
        
        return results;
    }
    
    private void scanForProjects(File dir, Pattern namePattern, List<java.io.File> results,
                                  Set<String> scanned, int depth, int maxDepth) {
        if (depth > maxDepth || !dir.isDirectory()) return;
        String absPath = dir.getAbsolutePath();
        if (scanned.contains(absPath)) return;
        scanned.add(absPath);
        
        File projectFile = new File(dir, ".project");
        if (projectFile.exists()) {
            String name = dir.getName();
            if (namePattern.matcher(name).matches()) {
                results.add(projectFile);
            }
        }
        
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            // Skip known non-project directories
            for (File sub : subdirs) {
                String n = sub.getName();
                if (n.equals("node_modules") || n.equals(".git") || n.equals("ccs_base")
                    || n.equals("Debug") || n.equals("Release") || n.equals("bin")
                    || n.equals("syscfg") || n.startsWith(".")) continue;
                scanForProjects(sub, namePattern, results, scanned, depth + 1, maxDepth);
            }
        }
    }
    
    /** Import a CCS project into the active workspace */
    public IProject importProject(java.io.File projectFile, IProgressMonitor monitor) {
        if (monitor == null) monitor = new NullProgressMonitor();
        try {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IPath locationPath = org.eclipse.core.runtime.Path.fromOSString(projectFile.getParentFile().getAbsolutePath());
            
            // Check if already imported
            String projectName = projectFile.getParentFile().getName();
            IProject existing = workspace.getRoot().getProject(projectName);
            if (existing.exists()) {
                log("Project already imported: " + projectName);
                return existing;
            }
            
            IProjectDescription desc = workspace.newProjectDescription(projectName);
            desc.setLocation(locationPath);
            
            // Set CCS nature
            IProjectNature ccsNature = null;
            try {
                desc.setNatureIds(new String[]{CCS_NATURE_ID});
            } catch (Exception e) {
                log("Note: Could not set CCS nature (may not be critical)");
            }
            
            IProject project = workspace.getRoot().getProject(projectName);
            project.create(desc, IResource.DEPTH_ONE, monitor);
            project.open(IResource.DEPTH_INFINITE, monitor);
            
            log("Project imported: " + project.getLocation().toOSString());
            return project;
            
        } catch (Exception e) {
            log("Import error: " + e.getMessage());
            return null;
        }
    }
    
    /** Build a CCS project and return true if successful */
    public boolean buildProject(IProject project, IProgressMonitor monitor) {
        if (monitor == null) monitor = new NullProgressMonitor();
        if (buildInProgress) {
            log("Build already in progress");
            return false;
        }
        buildInProgress = true;
        
        try {
            log("Starting build: " + project.getName());
            long start = System.currentTimeMillis();
            
            // Get the project's build configuration
            ICommand[] buildCmds = project.getDescription().getBuildSpec();
            boolean hasCCBuild = false;
            for (ICommand cmd : buildCmds) {
                if ("org.eclipse.cdt.managedbuilder.core.managedBuildNature".equals(cmd.getBuilderName())
                    || "com.ti.ccstudio.builder.CCSBuildCallbacks".equals(cmd.getBuilderName())) {
                    hasCCBuild = true;
                    break;
                }
            }
            
            if (!hasCCBuild) {
                log("No CCS/TI build nature found, trying standard build");
            }
            
            // Trigger build using IWorkspaceRunnable
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            workspace.run(new IWorkspaceRunnable() {
                @Override
                public void run(IProgressMonitor m) throws CoreException {
                    project.build(IncrementalProjectBuilder.FULL_BUILD, m);
                }
            }, monitor);
            
            long elapsed = System.currentTimeMillis() - start;
            boolean hasErrors = hasBuildErrors(project);
            
            if (hasErrors) {
                log("Build completed with ERRORS (" + elapsed + "ms)");
                return false;
            } else {
                log("Build SUCCEEDED (" + elapsed + "ms)");
                return true;
            }
            
        } catch (Exception e) {
            log("Build exception: " + e.getMessage());
            return false;
        } finally {
            buildInProgress = false;
        }
    }
    
    /** Collect build errors as structured objects */
    public List<BuildError> collectBuildErrors(IProject project, IProgressMonitor monitor) {
        List<BuildError> errors = new ArrayList<>();
        try {
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            for (IMarker m : markers) {
                Integer severity = (Integer) m.getAttribute(IMarker.SEVERITY, -1);
                if (severity != null && (severity == IMarker.SEVERITY_ERROR || severity == IMarker.SEVERITY_WARNING)) {
                    BuildError err = new BuildError();
                    err.message = (String) m.getAttribute(IMarker.MESSAGE, "");
                    err.resourceName = m.getResource().getName();
                    err.location = (String) m.getAttribute(IMarker.LOCATION, "");
                    err.lineNumber = (Integer) m.getAttribute(IMarker.LINE_NUMBER, -1);
                    err.severity = severity == IMarker.SEVERITY_ERROR ? "ERROR" : "WARNING";
                    
                    // Get character offsets for code context
                    Integer charStart = (Integer) m.getAttribute(IMarker.CHAR_START, -1);
                    Integer charEnd = (Integer) m.getAttribute(IMarker.CHAR_END, -1);
                    if (charStart != null && charEnd != null && charStart >= 0) {
                        try {
                            IEditorPart editor = findEditorForResource(m.getResource());
                            if (editor != null) {
                                ITextSelection sel = (ITextSelection) editor.getSite()
                                    .getSelectionProvider().getSelection();
                                err.lineContent = sel.getText();
                            }
                        } catch (Exception ex) { /* ignore */ }
                    }
                    
                    errors.add(err);
                }
            }
        } catch (Exception e) {
            log("Error collecting markers: " + e.getMessage());
        }
        return errors;
    }
    
    /** Check if project has build errors */
    public boolean hasBuildErrors(IProject project) {
        try {
            IMarker[] errors = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            for (IMarker m : errors) {
                Integer sev = (Integer) m.getAttribute(IMarker.SEVERITY, -1);
                if (sev != null && sev == IMarker.SEVERITY_ERROR) return true;
            }
        } catch (Exception e) { /* ignore */ }
        return false;
    }
    
    /** AI-powered auto-fix of build errors */
    public boolean autoFixErrors(IProject project, List<BuildError> errors, IProgressMonitor monitor) {
        if (errors.isEmpty()) return true;
        
        try {
            // Format errors for AI
            StringBuilder errorReport = new StringBuilder();
            errorReport.append("CCS Build Errors (attempt #").append(fixAttemptCount.get()).append("):\n");
            for (int i = 0; i < Math.min(errors.size(), 20); i++) {
                BuildError err = errors.get(i);
                errorReport.append("[").append(err.severity).append("] ");
                errorReport.append(err.resourceName);
                if (err.lineNumber > 0) errorReport.append(":").append(err.lineNumber);
                errorReport.append(": ").append(err.message);
                errorReport.append("\n");
            }
            if (errors.size() > 20) {
                errorReport.append("... and ").append(errors.size() - 20).append(" more errors\n");
            }
            
            String prompt = "You are a C2000 CCS embedded developer assistant. "
                + "The following CCS project build has errors. "
                + "Analyze them and provide FIXED source code for the files that need changes.\n\n"
                + "IMPORTANT RULES:\n"
                + "1. Only provide code that is DIFFERENT from the original (patch/diff style)\n"
                + "2. For each file that needs fixing, output: FILENAME: <relative_path>\n"
                + "   Then the COMPLETE corrected code\n"
                + "3. Use CCS C2000 compiler compatible code (TI compiler)\n"
                + "4. Keep the overall project structure the same\n"
                + "5. Do NOT change code that is not related to the errors\n\n"
                + "Error report:\n" + errorReport.toString() + "\n\n"
                + "For each error, identify the file and provide the complete fixed code.\n"
                + "Format:\n"
                + "FILENAME: src/main.c\n"
                + "[full corrected file content]\n"
                + "---\n"
                + "FILENAME: src/config.h\n"
                + "[full corrected file content]";
            
            // Read the project files
            Map<String, String> projectFiles = readProjectSourceFiles(project, monitor);
            StringBuilder context = new StringBuilder();
            context.append("Project source files:\n\n");
            for (Map.Entry<String, String> entry : projectFiles.entrySet()) {
                context.append("=== FILE: ").append(entry.getKey()).append(" ===\n");
                context.append(entry.getValue()).append("\n\n");
            }
            
            final StringBuilder aiResponse = new StringBuilder();
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = {false};
            
            aiClient.sendMessageStream(context.toString() + "\n" + prompt, new AIClient.StreamCallback() {
                @Override public void onChunk(String text) { aiResponse.append(text); }
                @Override public void onComplete(String usage) {
                    success[0] = applyAIFixes(project, aiResponse.toString(), monitor);
                    latch.countDown();
                }
                @Override public void onError(String error) {
                    log("AI fix error: " + error);
                    latch.countDown();
                }
            });
            
            latch.await(5, TimeUnit.MINUTES);
            return success[0];
            
        } catch (Exception e) {
            log("Auto-fix error: " + e.getMessage());
            return false;
        }
    }
    
    /** Parse AI response and apply code fixes to project files */
    private boolean applyAIFixes(IProject project, String aiResponse, IProgressMonitor monitor) {
        if (aiResponse == null || aiResponse.isEmpty()) return false;
        
        try {
            Pattern filePat = Pattern.compile("(?m)^FILENAME:\\s*(.+?)\\s*$");
            Pattern codeBlockPat = Pattern.compile("```(?:\\w+)?\\s*([\\s\\S]*?)```");
            Matcher fileMatcher = filePat.matcher(aiResponse);
            
            int applied = 0;
            int failed = 0;
            
            while (fileMatcher.find()) {
                String fileName = fileMatcher.group(1).trim();
                int start = fileMatcher.end();
                int end = aiResponse.indexOf("FILENAME:", start);
                String section = end > 0 ? aiResponse.substring(start, end) : aiResponse.substring(start);
                
                // Extract code from code blocks or raw content
                String code;
                Matcher codeMatcher = codeBlockPat.matcher(section);
                if (codeMatcher.find()) {
                    code = codeMatcher.group(1);
                } else {
                    // Remove markdown headers and separators
                    code = section.replaceAll("^---+.*$", "").replaceAll("^===.*$", "").trim();
                }
                
                // Find the actual file in the project
                IFile targetFile = findFileInProject(project, fileName, monitor);
                if (targetFile != null) {
                    try {
                        // Backup original
                        backupFile(targetFile);
                        
                        // Apply fix
                        InputStream newContent = new ByteArrayInputStream(code.getBytes("UTF-8"));
                        targetFile.setContents(newContent, IResource.FORCE, monitor);
                        log("Fixed: " + fileName);
                        applied++;
                    } catch (Exception e) {
                        log("Failed to apply fix to " + fileName + ": " + e.getMessage());
                        failed++;
                    }
                } else {
                    log("Could not locate file in project: " + fileName);
                    failed++;
                }
            }
            
            log("Applied " + applied + " fix(es), " + failed + " failed");
            return applied > 0;
            
        } catch (Exception e) {
            log("Error applying AI fixes: " + e.getMessage());
            return false;
        }
    }
    
    /** Read all source files from a project */
    private Map<String, String> readProjectSourceFiles(IProject project, IProgressMonitor monitor) {
        Map<String, String> files = new LinkedHashMap<>();
        try {
            collectSourceFiles(project, files, "", monitor);
        } catch (Exception e) {
            log("Error reading project files: " + e.getMessage());
        }
        return files;
    }
    
    private void collectSourceFiles(IResource res, Map<String, String> files, String basePath,
                                     IProgressMonitor monitor) {
        try {
            if (monitor != null && monitor.isCanceled()) return;
            if (res instanceof IFile) {
                IFile file = (IFile) res;
                String name = file.getName().toLowerCase();
                if (name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".cpp")
                    || name.endsWith(".asm") || name.endsWith(".cmd") || name.endsWith(".py")) {
                    String fullPath = basePath.isEmpty() ? file.getName() : basePath + "/" + file.getName();
                    try {
                        files.put(fullPath, readFileContent(file));
                    } catch (Exception e) { /* skip binary or unreadable files */ }
                }
            } else if (res instanceof IContainer) {
                for (IResource child : ((IContainer) res).members()) {
                    String childName = child.getName();
                    if (childName.equals("Debug") || childName.equals("Release")
                        || childName.equals("bin") || childName.equals(".metadata")
                        || childName.startsWith(".")) continue;
                    collectSourceFiles(child, files,
                        basePath.isEmpty() ? child.getName() : basePath + "/" + child.getName(),
                        monitor);
                }
            }
        } catch (Exception e) { /* skip inaccessible resources */ }
    }
    
    private String readFileContent(IFile file) throws IOException, CoreException {
        try (InputStream is = file.getContents()) {
            return new String(is.readAllBytes(), "UTF-8");
        }
    }
    
    private IFile findFileInProject(IProject project, String relativePath, IProgressMonitor monitor) {
        try {
            IFile file = project.getFile(relativePath);
            if (file.exists()) return file;
            
            // Try without base path
            java.io.File projRoot = project.getLocation().toFile();
            java.io.File target = new java.io.File(projRoot, relativePath.replace("/", "\\"));
            if (target.exists() && target.isFile()) {
                return project.getFile(relativePath.replace("\\", "/"));
            }
            
            // Search recursively
            return searchFileInProject(project, new java.io.File(relativePath).getName(), monitor);
        } catch (Exception e) {
            return null;
        }
    }
    
    private IFile searchFileInProject(IProject project, String fileName, IProgressMonitor monitor) {
        try {
            final IFile[] found = new IFile[1];
            project.accept(new IResourceProxyVisitor() {
                public boolean visit(IResourceProxy proxy) {
                    if (proxy.getType() == IResource.FILE && proxy.getName().equals(fileName)) {
                        found[0] = (IFile) proxy.requestResource();
                        return false;
                    }
                    return true;
                }
            }, IResource.DEPTH_INFINITE);
            return found[0];
        } catch (Exception e) {
            return null;
        }
    }
    
    private IEditorPart findEditorForResource(IResource resource) {
        try {
            IEditorReference[] editors = PlatformUI.getWorkbench()
                .getActiveWorkbenchWindow().getActivePage().getEditorReferences();
            for (IEditorReference ref : editors) {
                if (resource.equals(ref.getEditor(false).getEditorInput())) {
                    return ref.getEditor(false);
                }
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }
    
    private void backupFile(IFile file) {
        try {
            java.io.File backupDir = new java.io.File(file.getProject().getLocation().toFile(), ".qclaw_backup");
            backupDir.mkdirs();
            String timeStr = java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            java.io.File backup = new java.io.File(backupDir, file.getName() + "." + timeStr + ".bak");
            try (InputStream is = file.getContents();
                 OutputStream os = new FileOutputStream(backup)) {
                is.transferTo(os);
            }
        } catch (Exception e) {
            log("Backup warning: " + e.getMessage());
        }
    }
    
    private void log(String msg) {
        try {
            consoleStream.println("[QClaw] " + msg);
        } catch (Exception e) { /* console may not be available */ }
    }
    
    public boolean isBuildInProgress() { return buildInProgress; }
    
    // ============================================================
    // Structured Build Error
    // ============================================================
    public static class BuildError {
        public String message;
        public String resourceName;
        public String location;
        public int lineNumber = -1;
        public String severity = "ERROR";
        public String lineContent = "";
    }
}
