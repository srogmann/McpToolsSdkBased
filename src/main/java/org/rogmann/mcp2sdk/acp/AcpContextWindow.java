package org.rogmann.mcp2sdk.acp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the effective context window (tokens) of the ACP chat's LLM — the {@code size}
 * the {@code usage_update} session update reports (schema v1: {@code used} and {@code size}
 * are required, and the spec says to send no usage_update at all without a meaningful size).
 *
 * <p>Two sources, combined as {@code min}:</p>
 * <ul>
 *   <li><b>Configured budget</b> — system property {@code acp.chat.context-window} (the
 *   {@code acp.ide-diff} pattern: re-read on every call, so a test is a one-liner without a
 *   restart). This is a deliberate knob, not a fallback: a user who wants a smaller window
 *   than the model offers (request-latency, cache or cost reasons) sets it, and every
 *   reported percentage shrinks accordingly.</li>
 *   <li><b>Detected window</b> — a GET at the backend's model list reads the context size
 *   from the model metadata: {@code data[].meta.n_ctx} (llama.cpp, e.g.
 *   {@code /v1/models} at 192.168.178.41:8000), falling back to {@code max_model_len}
 *   (vLLM) and {@code max_input_tokens} (LiteLLM {@code /model/info} shape). Looked up by
 *   {@code webui.model.name}, cached per URL+model for the process lifetime; a failed
 *   detection is retried on the next call, so a backend that starts late heals itself.</li>
 * </ul>
 *
 * <p>Detection is best effort: 0 means "unknown" — the caller then either has the configured
 * budget or reports nothing.</p>
 */
final class AcpContextWindow {

    /** Budget override (tokens): system property {@code acp.chat.context-window}. */
    static final String PROP_CONTEXT_WINDOW = "acp.chat.context-window";

    private static final Logger LOG = LoggerFactory.getLogger(AcpContextWindow.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Detection cache per "url|model" — successes only, so a retry can heal a failure. */
    private static final Map<String, Long> DETECTED = new ConcurrentHashMap<>();

    private AcpContextWindow() {
    }

    /**
     * The effective context window in tokens: {@code min(configured, detected)}; 0 when
     * neither source names a window.
     */
    static long effectiveWindow(String modelUrl, String modelName) {
        long configured = configuredWindow();
        long detected = detectedWindow(modelUrl, modelName);
        if (configured > 0 && detected > 0) {
            return Math.min(configured, detected);
        }
        return Math.max(configured, detected);
    }

    /** The configured budget (tokens); 0 when unset or invalid (logged, not thrown). */
    static long configuredWindow() {
        String raw = System.getProperty(PROP_CONTEXT_WINDOW, "");
        if (raw.isBlank()) {
            return 0;
        }
        try {
            long value = Long.parseLong(raw.strip());
            if (value <= 0) {
                LOG.warn("Ignoring {}={} (must be a positive token count)", PROP_CONTEXT_WINDOW, raw);
                return 0;
            }
            return value;
        }
        catch (NumberFormatException e) {
            LOG.warn("Ignoring {}='{}' (not a number)", PROP_CONTEXT_WINDOW, raw);
            return 0;
        }
    }

    /** The detected window (tokens), cached; 0 when unreachable, unparsable or model unknown. */
    private static long detectedWindow(String modelUrl, String modelName) {
        if (modelUrl == null || modelUrl.isBlank()) {
            return 0;
        }
        String key = modelUrl.strip() + "|" + (modelName == null ? "" : modelName.strip());
        Long cached = DETECTED.get(key);
        if (cached != null) {
            return cached;
        }
        long detected = fetchContextWindow(modelUrl.strip(),
                modelName == null ? "" : modelName.strip());
        if (detected > 0) {
            DETECTED.put(key, detected);
        }
        return detected;
    }

    private static long fetchContextWindow(String modelUrl, String modelName) {
        String url = modelsUrl(modelUrl);
        HttpURLConnection connection = null;
        try {
            URL target = URI.create(url).toURL();
            connection = (HttpURLConnection) target.openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(2_000);
            int status = connection.getResponseCode();
            if (status != 200) {
                LOG.debug("Context-window detection: HTTP {} from {}", status, url);
                return 0;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            JsonNode data = JSON.readTree(body.toString()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                LOG.debug("Context-window detection: no model list at {}", url);
                return 0;
            }
            JsonNode match = null;
            if (!modelName.isBlank()) {
                for (JsonNode entry : data) {
                    if (modelName.equals(entry.path("id").asString(""))) {
                        match = entry;
                        break;
                    }
                }
            }
            if (match == null && data.size() == 1) {
                match = data.get(0);
            }
            if (match == null) {
                LOG.debug("Context-window detection: model '{}' not among {} entries at {}",
                        modelName, data.size(), url);
                return 0;
            }
            long window = match.path("meta").path("n_ctx").asLong(0);
            if (window <= 0) {
                window = match.path("max_model_len").asLong(0);
            }
            if (window <= 0) {
                window = match.path("max_input_tokens").asLong(0);
            }
            if (window > 0) {
                LOG.info("Context window of '{}' detected: {} tokens ({})",
                        match.path("id").asString("?"), window, url);
            }
            else {
                LOG.debug("Context-window detection: no n_ctx/max_model_len/max_input_tokens at {}", url);
            }
            return window;
        }
        catch (IOException | RuntimeException e) {
            LOG.debug("Context-window detection failed for {}: {}", url, e.getMessage());
            return 0;
        }
        finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * The model-list URL of the backend: {@code webui.model.url} is a base the proxy appends
     * the request path to, so every realistic shape is normalized here — with or without a
     * trailing slash, with or without {@code /v1}, or as a full completions URL.
     */
    private static String modelsUrl(String modelUrl) {
        String url = modelUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            url = url.substring(0, url.length() - "/chat/completions".length());
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1")) {
            return url + "/models";
        }
        return url + "/v1/models";
    }
}
