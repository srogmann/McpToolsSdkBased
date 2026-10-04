package org.rogmann.mcp2sdk.browser;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a request <b>carries</b>: the field <em>names</em> of one payload (M2b, docs/browser.md §7a).
 *
 * <p>The field model is built from what the page shows, and the page shows less than it sends:
 * {@code bridge.js} skips {@code <input type=hidden>} in the snapshot and in the value pass on purpose
 * (they are noise while reading), so a name the application receives can stay unknown. The other half
 * of the same blind spot: a field whose value the application <em>hands out</em> - a decoder's output
 * box, a counter, a generated reference - arrives with a new page view, so no comparison inside one
 * document can ever call it produced. Both questions have one answer: look at what leaves the page.
 * That answer is not new data, it is a join - the bodies already lie redacted in
 * {@code runs/browser/<session>/requests.jsonl}.</p>
 *
 * <p><b>Redaction first.</b> Fed with the text <em>after</em> {@link BrowserSecrets} has worked on it
 * (and after the {@code sensitivePaths} decision), never with the raw body. Names survive that step -
 * they are the shape of the request - while a secret value already stands as {@code ‹redacted›}. So
 * this class cannot un-mask anything, and it reports a masked value as "no value" instead of as a
 * digest of the mask. An exchange on a sensitive path yields <b>no names at all</b> and a
 * {@link #Reading#gap() gap}: what we were not allowed to read must not count as proof that a name was
 * not in it.</p>
 *
 * <p><b>Names, not controls.</b> A payload says "this name went out with this value". It never says
 * which frame, form or control it came from, which is exactly why {@link BrowserFieldModel} joins by
 * <em>name</em> and says so in the row it produces.</p>
 */
final class BrowserPayload {

    /**
     * One name of a payload with the value text that stood behind it.
     *
     * @param name the field name as it appeared on the wire (never decoded, never guessed)
     * @param value the value as it stands in the <em>redacted</em> capture - empty when there was
     *              none, {@link BrowserSecrets#MASK} inside when the value was a secret
     */
    record Field(String name, String value) {
    }

    /**
     * Result of reading one payload.
     *
     * @param fields  the names it carries (empty when nothing could be read)
     * @param formish true when the payload came from something that behaves like a {@code <form>} -
     *                the only names of this exchange that may become a field of the model themselves
     * @param gap     why a body that was there could not be read, empty when the read is complete.
     *                A gap is not "no fields", it is "no statement" (see {@link BrowserFieldModel})
     */
    record Reading(List<Field> fields, boolean formish, String gap) {

        /** @return true when this exchange carried nothing that belongs in the model */
        boolean silent() {
            return fields.isEmpty() && gap.isEmpty();
        }
    }

    /** An exchange that carries no payload at all (a GET of an asset, a POST without a body). */
    private static final Reading NOTHING = new Reading(List.of(), false, "");

    /**
     * Parsing window. A form a human filled in is never this long; a body beyond it is read as far as
     * this and then named a gap rather than pretended to be complete.
     */
    private static final int MAX_PARSE_CHARS = 64 * 1024;

    /** How many names of one payload are counted (a wider form is worth a debug line, not a model). */
    private static final int MAX_NAMES = 200;

    /**
     * A name as it appears in a form pair. Deliberately strict on the first character and on the rest:
     * the junk of a beacon body must not read as a list of field names, and a wrong "not seen" is
     * worse than no statement at all. Percent-encoded names are rare and stay un-decoded - what is
     * compared is what the application receives.
     */
    private static final Pattern PAIR_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-\\[\\]]{0,79}");

    /**
     * {@code name="…"} of a multipart part header. The lookbehind is not decoration: without it the
     * {@code name=} of {@code filename="note.txt"} matches too, and a file name would enter the model
     * as a field name of the form.
     */
    private static final Pattern MULTIPART_NAME =
            Pattern.compile("(?<![A-Za-z0-9_])name\\s*=\\s*\"?([^\"\\r\\n;,]{1,80})\"?");

    private BrowserPayload() {
    }

    /**
     * Reads the names of one proxied request.
     *
     * @param method      HTTP method (only a question of shape, not of trust)
     * @param contentType content type of the request, may be {@code null}
     * @param body        the request text <b>after redaction, before clipping</b> - clipping would
     *                    hide the tail of a form and a missing tail reads as "not sent"
     * @param query       the query of the request, may be {@code null} or empty
     * @param sensitive   true when the exchange stands in the capture without its body
     *                    ({@code sensitivePaths})
     * @return what could be read - names, whether they came from a form, and the reason they did not
     */
    static Reading read(String method, String contentType, String body, String query,
            boolean sensitive) {
        String text = body == null ? "" : body.trim();
        String q = query == null ? "" : query.trim();
        if (text.isEmpty() && q.isEmpty()) {
            return NOTHING;                                   // nothing left the page but the URL
        }
        if (sensitive) {
            // Redaction first: no name, and a gap. The login form of an application is exactly the
            // payload whose absence must not teach the model that a field is never sent.
            return new Reading(List.of(), false,
                    "sensitive path - body and query were not logged, so nothing was read");
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean formType = type.contains("form-urlencoded") || type.contains("multipart")
                || type.contains("json");
        if (text.isEmpty()) {
            return queryPayload(q);
        }
        List<Field> carried;
        if (type.contains("multipart")) {
            carried = multipartNames(text);
        } else if (text.startsWith("{") || text.startsWith("[")) {
            carried = jsonNames(text);
        } else {
            carried = pairNames(text);
        }
        if (carried.isEmpty()) {
            // A body that is there and cannot be read: it could have carried anything, so the page
            // this request went to gets a gap and no claim built on absence. (A beacon posting
            // random text to its own endpoint therefore marks <em>that</em> endpoint, not the form.)
            return new Reading(List.of(), false, formType
                    ? "a form-typed payload held no readable field name"
                    : "body is not a form (" + (type.isEmpty() ? "no content type" : type) + ")");
        }
        // Names found in a body that does not claim to be a form (a fetch() without a content type
        // still carries `a=1&b=2`) count as a presence fact - they can only ever <em>prevent</em> a
        // claim - but they create no field of their own, because nothing here says a control exists.
        return new Reading(carried, formType, "");
    }

    /**
     * A GET carries its form names in the query - the demo's search form does exactly that. One name
     * alone stays what it is: a link pointing at a record ({@code ?id=DID-0013}), not a submission.
     *
     * @param query the query string
     * @return the reading
     */
    private static Reading queryPayload(String query) {
        List<Field> names = pairNames(query);
        if (names.size() < 2) {
            return NOTHING;
        }
        return new Reading(names, true, "");
    }

    /**
     * @param text urlencoded body or query
     * @return the pairs whose name looks like a field name, in order
     */
    private static List<Field> pairNames(String text) {
        List<Field> out = new ArrayList<>();
        String window = text.length() > MAX_PARSE_CHARS ? text.substring(0, MAX_PARSE_CHARS) : text;
        for (String part : window.split("[&;]")) {
            if (out.size() >= MAX_NAMES) {
                return out;
            }
            int equals = part.indexOf('=');
            String name = (equals < 0 ? part : part.substring(0, equals)).trim();
            if (name.isEmpty() || !PAIR_NAME.matcher(name).matches()) {
                continue;
            }
            String value = equals < 0 ? "" : part.substring(equals + 1).trim();
            out.add(new Field(name, value));
        }
        return out;
    }

    /**
     * @param text the redacted text of a {@code multipart/form-data} body
     * @return the names of its parts
     */
    private static List<Field> multipartNames(String text) {
        List<Field> out = new ArrayList<>();
        String window = text.length() > MAX_PARSE_CHARS ? text.substring(0, MAX_PARSE_CHARS) : text;
        Matcher matcher = MULTIPART_NAME.matcher(window);
        while (matcher.find() && out.size() < MAX_NAMES) {
            String name = matcher.group(1).trim();
            if (PAIR_NAME.matcher(name).matches()) {
                out.add(new Field(name, ""));
            }
        }
        return out;
    }

    /**
     * Top-level keys of a JSON body (the depth of one level is what a payload's field names are; a
     * nested object is one value, not a field of the form).
     *
     * @param text the redacted body
     * @return the keys, empty when the text is not readable JSON
     */
    private static List<Field> jsonNames(String text) {
        List<Field> out = new ArrayList<>();
        String window = text.length() > MAX_PARSE_CHARS ? text.substring(0, MAX_PARSE_CHARS) : text;
        try {
            JsonNode root = BrowserSessions.get().mapper().readTree(window);
            if (root.isObject()) {
                root.propertyNames().forEach(name -> addIfName(out, name, ""));
            } else if (root.isArray()) {
                // An array of records: the keys of its elements are the names the application receives.
                for (JsonNode element : root) {
                    if (out.size() >= MAX_NAMES) {
                        break;
                    }
                    if (element.isObject()) {
                        element.propertyNames().forEach(name -> addIfName(out, name, ""));
                    }
                }
            }
        } catch (RuntimeException e) {
            // A body that starts like JSON but is clipped or malformed: the caller turns the empty
            // list into a gap, which is the honest reading of "we could not finish this".
            return List.of();
        }
        return out;
    }

    private static void addIfName(List<Field> out, String name, String value) {
        String clean = name == null ? "" : name.trim();
        if (!clean.isEmpty() && PAIR_NAME.matcher(clean).matches() && out.size() < MAX_NAMES) {
            out.add(new Field(clean, value));
        }
    }
}
