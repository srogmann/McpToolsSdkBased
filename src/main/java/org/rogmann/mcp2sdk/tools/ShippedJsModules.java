package org.rogmann.mcp2sdk.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The JavaScript libraries that travel with the server inside its jar.
 * <p>
 * The manifest {@code js/tools/modules.json} is the <b>only</b> list of them: a caller writes
 * {@code loadShipped('pdftext')}, and the name is looked up here - not searched for. A jar has no
 * directory listing, and a search would (a) expose every {@code .js} of every classpath entry to
 * the sandbox and (b) leave the question open which version ran. A declaration answers both: what
 * is not in this file cannot be loaded, and the summary travels into the tool description.
 * </p>
 * <p>
 * The manifest is a build artefact, so a defect in it is not a user error: it aborts with an
 * {@link IllegalStateException} naming the entry (the same rule the project applies to
 * configuration properties - no silent fallback).
 * </p>
 */
final class ShippedJsModules {

    /** The directory inside the classpath that the shipped libraries live in. */
    static final String ROOT = "js/tools/";

    /** The manifest that names them. */
    static final String MANIFEST = "js/tools/modules.json";

    /** A module name: lowercase letters, digits and '-' (it appears in a JS call). */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

    /** The declared modules by name, in manifest order. */
    private static final Map<String, Entry> BY_NAME = load();

    /** The declared modules by resource name (relative to {@link #ROOT}). */
    private static final Map<String, Entry> BY_FILE = byFile(BY_NAME);

    private ShippedJsModules() {
        // Utility class
    }

    /**
     * One shipped library.
     * @param name the name a caller writes ({@code loadShipped('pdftext')})
     * @param file the file below {@link #ROOT} ({@code lib/pdftext.js})
     * @param summary one sentence for the tool description
     */
    record Entry(String name, String file, String summary) {
    }

    /**
     * The declared modules.
     * @return the entries by name, in manifest order
     */
    static Map<String, Entry> byName() {
        return BY_NAME;
    }

    /**
     * Looks a module up by its resource name - the way a relative {@code require} inside a shipped
     * module names its neighbour.
     * @param file the resource relative to {@link #ROOT}
     * @return the entry, or {@code null} if the file is not declared
     */
    static Entry byFile(String file) {
        return BY_FILE.get(file);
    }

    /**
     * Reads the manifest from the classpath.
     * @return the entries by name
     */
    private static Map<String, Entry> load() {
        byte[] bytes;
        try (InputStream in = ShippedJsModules.class.getClassLoader()
                .getResourceAsStream(MANIFEST)) {
            if (in == null) {
                throw new IllegalStateException("The shipped-module manifest is missing on the"
                        + " classpath: " + MANIFEST + " (it belongs to src/main/resources)");
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the shipped-module manifest "
                    + MANIFEST + ": " + e.getMessage(), e);
        }
        JsonNode root;
        try {
            root = JsonMapper.builder().build()
                    .readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalStateException("The shipped-module manifest " + MANIFEST
                    + " is not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalStateException("The shipped-module manifest " + MANIFEST
                    + " must be an object of name -> {file, summary}");
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            String name = field.getKey();
            requireName(name);
            JsonNode value = field.getValue();
            if (!value.isObject()) {
                throw new IllegalStateException(MANIFEST + ": '" + name + "' must be an object"
                        + " {file, summary}");
            }
            String file = text(value, "file", name);
            requireFile(name, file);
            String summary = text(value, "summary", name);
            entries.put(name, new Entry(name, file, summary));
        }
        return Collections.unmodifiableMap(entries);
    }

    /**
     * Builds the reverse map and refuses a file that is declared twice.
     * @param byName the entries by name
     * @return the entries by resource name
     */
    private static Map<String, Entry> byFile(Map<String, Entry> byName) {
        Map<String, Entry> byFile = new LinkedHashMap<>();
        for (Entry entry : byName.values()) {
            Entry clash = byFile.put(entry.file(), entry);
            if (clash != null) {
                throw new IllegalStateException(MANIFEST + ": the file '" + entry.file()
                        + "' is declared twice ('" + clash.name() + "' and '" + entry.name() + "')");
            }
        }
        return Collections.unmodifiableMap(byFile);
    }

    /**
     * Reads a required text member of a manifest entry.
     * @param value the entry object
     * @param member the member name
     * @param name the module name (for the message)
     * @return the text
     */
    private static String text(JsonNode value, String member, String name) {
        JsonNode node = value.get(member);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw new IllegalStateException(MANIFEST + ": '" + name + "." + member
                    + "' must be a non-empty string");
        }
        return node.asString();
    }

    /**
     * Refuses a name a caller could not write.
     * @param name the module name
     */
    private static void requireName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalStateException(MANIFEST + ": the module name '" + name + "' must be"
                    + " lowercase letters, digits and '-' (" + NAME.pattern() + ")");
        }
    }

    /**
     * Refuses a file that is not a plain {@code .js} below {@link #ROOT}.
     * @param name the module name (for the message)
     * @param file the declared file
     */
    private static void requireFile(String name, String file) {
        if (!file.toLowerCase(Locale.ROOT).endsWith(".js")) {
            throw new IllegalStateException(MANIFEST + ": '" + name + ".file' is '" + file
                    + "' - only '.js' is loaded as a module");
        }
        if (file.startsWith("/") || file.contains("..") || file.contains("\\")) {
            throw new IllegalStateException(MANIFEST + ": '" + name + ".file' is '" + file
                    + "' - a file is a plain name below " + ROOT + " (no '/..', no backslash)");
        }
    }
}
