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

public class AIClient {

    private static final String GATEWAY_HOST = "127.0.0.1";
    private static final int GATEWAY_PORT = 50264;
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String AUTH_TOKEN = "cd5cbe926a503c5098ce18d21d52b69b62e1104e1738a056";
    private static final String MODEL = "openclaw";

    private final List<Message> conversationHistory = new ArrayList<>();

    public AIClient() {
        conversationHistory.add(new Message("system",
            "You are a CCS AI assistant helping embedded developers with code issues." +
            "The user is using TI Code Composer Studio (CCS) for embedded development." +
            "Respond in concise Chinese. Be accurate with code examples."));
    }

    public String sendMessage(String userMessage) throws Exception {
        conversationHistory.add(new Message("user", userMessage));
        String jsonBody = buildRequestBody(false);

        URL url = new URL("http://" + GATEWAY_HOST + ":" + GATEWAY_PORT + CHAT_COMPLETIONS_PATH);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);
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
        void onComplete();
        void onError(String error);
    }

    /** Stream response to callback (for real-time display) */
    public void sendMessageStream(String userMessage, StreamCallback callback) {
        conversationHistory.add(new Message("user", userMessage));
        String jsonBody = buildRequestBody(true);

        Thread streamThread = new Thread(() -> {
            try {
                URL url = new URL("http://" + GATEWAY_HOST + ":" + GATEWAY_PORT + CHAT_COMPLETIONS_PATH);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);
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
                String line;

                while ((line = reader.readLine()) != null) {
                    // SSE format: "data: {...}" or "data: [DONE]"
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if (data.equals("[DONE]")) break;

                        String chunk = extractDeltaContent(data);
                        if (chunk != null && !chunk.isEmpty()) {
                            fullResponse.append(chunk);
                            callback.onChunk(chunk);
                        }
                    }
                }
                reader.close();

                // Add to conversation history
                String content = fullResponse.toString();
                if (!content.isEmpty()) {
                    conversationHistory.add(new Message("assistant", content));
                }
                callback.onComplete();

            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
        streamThread.setDaemon(true);
        streamThread.start();
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
        body.append("{\"model\":\"").append(MODEL).append("\"");
        body.append(",\"messages\":[");
        for (int i = 0; i < conversationHistory.size(); i++) {
            Message msg = conversationHistory.get(i);
            if (i > 0) body.append(",");
            body.append("{\"role\":\"").append(msg.role).append("\"");
            body.append(",\"content\":\"").append(escapeJson(msg.content)).append("\"}");
        }
        body.append("],\"max_tokens\":2048,\"stream\":").append(stream ? "true" : "false").append("}");
        return body.toString();
    }

    private String buildRawRequestBody(String userMessage, boolean stream) {
        StringBuilder body = new StringBuilder();
        body.append("{\"model\":\"").append(MODEL).append("\"");
        body.append(",\"messages\":[");
        body.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(
            "You are a CCS AI assistant helping embedded developers with code issues." +
            "The user is using TI Code Composer Studio (CCS) for embedded development." +
            "Respond in concise Chinese. Be accurate with code examples.")).append("\"}");
        body.append(",{\"role\":\"user\",\"content\":\"").append(escapeJson(userMessage)).append("\"}");
        body.append("],\"max_tokens\":2048,\"stream\":").append(stream ? "true" : "false").append("}");
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

    public String sendRawMessage(String userMessage) throws Exception {
        String jsonBody = buildRawRequestBody(userMessage, false);

        URL url = new URL("http://" + GATEWAY_HOST + ":" + GATEWAY_PORT + CHAT_COMPLETIONS_PATH);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + AUTH_TOKEN);
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
        return content != null ? content : "(No response)";
    }

    public void clearHistory() {
        while (conversationHistory.size() > 1) { conversationHistory.remove(1); }
    }

    private static class Message {
        String role;
        String content;
        Message(String role, String content) { this.role = role; this.content = content; }
    }
}