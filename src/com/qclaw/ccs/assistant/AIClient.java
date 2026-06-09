package com.qclaw.ccs.assistant;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIClient {

    private String gatewayHost = "127.0.0.1";
    private int gatewayPort = 50264;
    private String authToken = "cd5cbe926a503c5098ce18d21d52b69b62e1104e1738a056";
    private String model = "openclaw";
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String DEFAULT_SYSTEM_PROMPT =
        "You are a CCS AI assistant helping embedded developers with code issues." +
        "The user is using TI Code Composer Studio (CCS) for embedded development." +
        "Respond in concise Chinese. Be accurate with code examples.";

    private final List<Message> conversationHistory = new ArrayList<>();
    private String historyFilePath = null;

    public AIClient() {
        conversationHistory.add(new Message("system", DEFAULT_SYSTEM_PROMPT));
    }

    // --- Configuration ---
    public void setConfig(String host, int port, String token, String model) {
        this.gatewayHost = host != null && !host.isEmpty() ? host : "127.0.0.1";
        this.gatewayPort = port > 0 ? port : 50264;
        this.authToken = token != null ? token : "";
        this.model = model != null && !model.isEmpty() ? model : "openclaw";
    }

    public String getConfigString() {
        return gatewayHost + ":" + gatewayPort + "|" + model;
    }

    public String getGatewayHost() { return gatewayHost; }
    public int getGatewayPort() { return gatewayPort; }
    public String getAuthToken() { return authToken; }
    public String getModel() { return model; }

    public void setHistoryFilePath(String path) {
        this.historyFilePath = path;
    }

    // --- HTTP methods ---
    public String sendMessage(String userMessage) throws Exception {
        conversationHistory.add(new Message("user", userMessage));
        String jsonBody = buildRequestBody(false);

        URL url = new URL("http://" + gatewayHost + ":" + gatewayPort + CHAT_COMPLETIONS_PATH);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + authToken);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(bodyBytes);
        os.flush();
        os.close();

        int responseCode = conn.getResponseCode();
        String responseBody = readStream(conn, responseCode);

        if (responseCode != 200) {
            throw new Exception("API error " + responseCode + ": " + responseBody);
        }

        String content = extractContent(responseBody);
        if (content != null && !content.isEmpty()) {
            conversationHistory.add(new Message("assistant", content));
        }
        return content != null ? content : "(No response)";
    }

    /** Callback interface for streaming responses */
    public interface StreamCallback {
        void onChunk(String text);
        void onComplete(String usage);  // usage JSON or null
        void onError(String error);
    }

    /** Stream response to callback (for real-time display) */
    public void sendMessageStream(String userMessage, StreamCallback callback) {
        conversationHistory.add(new Message("user", userMessage));
        String jsonBody = buildRequestBody(true);

        Thread streamThread = new Thread(() -> {
            try {
                URL url = new URL("http://" + gatewayHost + ":" + gatewayPort + CHAT_COMPLETIONS_PATH);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + authToken);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(120000);
                conn.setDoOutput(true);

                byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                OutputStream os = conn.getOutputStream();
                os.write(bodyBytes);
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    String errorBody = readStream(conn, responseCode);
                    callback.onError("API error " + responseCode + ": " + errorBody);
                    return;
                }

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder fullResponse = new StringBuilder();
                String usageJson = "";
                String line;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if (data.equals("[DONE]")) break;

                        if (data.contains("\"usage\"")) {
                            usageJson = data;
                        }

                        String chunk = extractDeltaContent(data);
                        if (chunk != null && !chunk.isEmpty()) {
                            fullResponse.append(chunk);
                            callback.onChunk(chunk);
                        }
                    }
                }
                reader.close();

                String content = fullResponse.toString();
                if (!content.isEmpty()) {
                    conversationHistory.add(new Message("assistant", content));
                }

                String usage = extractUsageFromChunk(usageJson);
                callback.onComplete(usage);

                // Auto-save history after each response
                saveHistory();

            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();
    }

    /** Extract usage object from a stream chunk */
    private String extractUsageFromChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return null;
        try {
            int usageIdx = chunk.indexOf("\"usage\"");
            if (usageIdx < 0) return null;
            int braceStart = chunk.indexOf('{', usageIdx);
            if (braceStart < 0) return null;
            int depth = 0;
            int end = braceStart;
            for (int i = braceStart; i < chunk.length(); i++) {
                if (chunk.charAt(i) == '{') depth++;
                else if (chunk.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) { end = i + 1; break; }
                }
            }
            return chunk.substring(braceStart, end);
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract delta.content from a streaming chunk JSON */
    private String extractDeltaContent(String json) {
        try {
            int deltaIdx = json.indexOf("\"delta\":{");
            if (deltaIdx < 0) return null;

            int contentIdx = json.indexOf("\"content\":", deltaIdx);
            if (contentIdx < 0) return null;

            int colonIdx = json.indexOf(":", contentIdx);
            if (colonIdx < 0) return null;

            int startQuote = -1;
            for (int i = colonIdx + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') { startQuote = i; break; }
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
            }
            if (startQuote < 0) return null;

            int endQuote = startQuote + 1;
            while (endQuote < json.length()) {
                char c = json.charAt(endQuote);
                if (c == '\\') { endQuote += 2; continue; }
                if (c == '"') break;
                endQuote++;
            }
            if (endQuote >= json.length()) return null;

            return json.substring(startQuote + 1, endQuote)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        } catch (Exception e) {
            return null;
        }
    }

    private String readStream(HttpURLConnection conn, int responseCode) throws Exception {
        java.io.InputStream is = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) return "(empty response)";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) { sb.append(line); }
        reader.close();
        return sb.toString();
    }

    private String buildRequestBody(boolean stream) {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(model).append("\"");
        body.append(",\"messages\":[");
        for (int i = 0; i < conversationHistory.size(); i++) {
            Message msg = conversationHistory.get(i);
            if (i > 0) body.append(",");
            body.append("{\"role\":\"").append(msg.role).append("\"");
            body.append(",\"content\":\"").append(escapeJson(msg.content)).append("\"}");
        }
        body.append("],\"max_tokens\":2048,\"stream\":").append(stream ? "true" : "false").append("}");
        if (stream) {
            body.append(",\"stream_options\":{\"include_usage\":true}");
        }
        return body.toString();
    }

    private String extractContent(String json) {
        try {
            int msgIdx = json.indexOf("\"message\":");
            if (msgIdx < 0) return null;

            int contentIdx = json.indexOf("\"content\"", msgIdx);
            if (contentIdx < 0) return null;

            int colonIdx = json.indexOf(":", contentIdx);
            if (colonIdx < 0) return null;

            int startQuote = -1;
            for (int i = colonIdx + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '"') { startQuote = i; break; }
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
            }
            if (startQuote < 0) return null;

            int endQuote = startQuote + 1;
            while (endQuote < json.length()) {
                char c = json.charAt(endQuote);
                if (c == '\\') { endQuote += 2; continue; }
                if (c == '"') break;
                endQuote++;
            }
            if (endQuote >= json.length()) return null;

            String content = json.substring(startQuote + 1, endQuote);
            return content.replace("\\n", "\n")
                          .replace("\\t", "\t")
                          .replace("\\\"", "\"")
                          .replace("\\\\", "\\");
        } catch (Exception e) {
            return "Parse error: " + e.getMessage();
        }
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    // --- Chat History Persistence (simple JSON) ---

    /** Save conversation history to file */
    public void saveHistory() {
        if (historyFilePath == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"messages\":[\n");
            for (int i = 0; i < conversationHistory.size(); i++) {
                Message msg = conversationHistory.get(i);
                if (i > 0) sb.append(",\n");
                sb.append("  {\"role\":\"").append(escapeJson(msg.role)).append("\"");
                sb.append(",\"content\":\"").append(escapeJson(msg.content)).append("\"}");
            }
            sb.append("\n]}");

            java.io.FileWriter fw = new java.io.FileWriter(historyFilePath);
            fw.write(sb.toString());
            fw.close();
        } catch (Exception e) {
            // Silent fail - history is non-critical
        }
    }

    /** Load conversation history from file */
    public boolean loadHistory() {
        if (historyFilePath == null) return false;
        java.io.File f = new java.io.File(historyFilePath);
        if (!f.exists()) return false;
        try {
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            // Simple parsing: extract role/content pairs
            List<Message> loaded = new ArrayList<>();
            int idx = 0;
            while ((idx = content.indexOf("\"role\":", idx)) >= 0) {
                int colonIdx = content.indexOf(":", idx + 7);
                int q1 = content.indexOf("\"", colonIdx + 1);
                int q2 = findClosingQuote(content, q1 + 1);
                if (q2 < 0) break;
                String role = content.substring(q1 + 1, q2).replace("\\\"", "\"");

                int cIdx = content.indexOf("\"content\":", q2);
                if (cIdx < 0) break;
                int cColon = content.indexOf(":", cIdx + 10);
                int cq1 = content.indexOf("\"", cColon + 1);
                int cq2 = findClosingQuote(content, cq1 + 1);
                if (cq2 < 0) break;
                String msgContent = content.substring(cq1 + 1, cq2)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\\\", "\\");

                loaded.add(new Message(role, msgContent));
                idx = cq2 + 1;
            }
            if (!loaded.isEmpty()) {
                conversationHistory.clear();
                conversationHistory.addAll(loaded);
                return true;
            }
        } catch (Exception e) {
            // Silent fail
        }
        return false;
    }

    /** Find the closing quote for a JSON string value */
    private int findClosingQuote(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"') return i;
            i++;
        }
        return -1;
    }

    /** Delete history file */
    public void deleteHistoryFile() {
        if (historyFilePath != null) {
            new java.io.File(historyFilePath).delete();
        }
    }

    /** Get last N messages as text for display */
    public String getRecentHistory(int count) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, conversationHistory.size() - count);
        for (int i = start; i < conversationHistory.size(); i++) {
            Message msg = conversationHistory.get(i);
            if ("system".equals(msg.role)) continue;
            String label = "user".equals(msg.role) ? "You" : "AI";
            sb.append(label).append(": ").append(msg.content).append("\n\n");
        }
        return sb.toString();
    }

    public void clearHistory() {
        while (conversationHistory.size() > 1) { conversationHistory.remove(1); }
        deleteHistoryFile();
    }

    private static class Message {
        String role;
        String content;
        Message(String role, String content) { this.role = role; this.content = content; }
    }
}
