package org.rogmann.mcp2sdk.browser;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts secrets <b>on the way in</b> to the capture.
 *
 * <p>The capture is not only a log, it is the LLM's context and later the replay corpus of learned
 * procedures. A rule that only kicks in when reading would already have lost: what once stands in the
 * history ring or in {@code requests.jsonl} is out. So this class alone redacts, and it does so on the
 * way in (decision L4 in docs/browser.md).</p>
 *
 * <p>Three classes of secrets, three treatments:</p>
 * <ol>
 *   <li><b>field names</b> ({@code password}, {@code otp}, ...): replace the value, <em>keep the name
 *       and the length</em>. The name carries the shape of the request, and {@code "password":"‹12›"}
 *       is enough for a replay in which the secret is fetched from the real field by the bridge at
 *       execution time - the LLM never needs the value.</li>
 *   <li><b>transport headers</b> ({@code Cookie}, {@code Authorization}, {@code Set-Cookie}): not as a
 *       value at all, only as a count. Whoever holds the session currency in the transcript holds the
 *       session - a "passwords are out" rule would be a figure-leaf rule without this point.</li>
 *   <li><b>digest-like values in credential fields</b>: a client-side hashed password looks harmless
 *       and is not (replayable like a password). Such a value is masked only when the <em>field
 *       name</em> sounds like one too ({@code hash}, {@code digest}, {@code signature}, {@code jwt},
 *       …) - a {@code checksum} or a hex {@code id} is no secret, and blindly masked value fields
 *       would take from the LLM exactly the data it is here for.</li>
 * </ol>
 */
public final class BrowserSecrets {

    /**
     * Predefined field names; compared without case, underscores and hyphens.
     *
     * <p>The German spellings are data, not prose, and are therefore deliberately not translated away:
     * this class matches against the field names of the <em>proxied application</em>, and in this
     * environment those fields are called {@code passwort} and {@code kennwort}. A rule that only knows
     * the English name is no rule. (The first test that found this was precisely the one with the
     * German field name.)</p>
     */
    public static final List<String> DEFAULT_KEYS = List.of(
            "password", "passwort", "passwd", "pwd", "kennwort", "passphrase", "secret", "geheimnis",
            "apikey", "authtoken", "accesstoken", "refreshtoken", "idtoken", "csrftoken", "token",
            "authorization", "authentifizierung", "otp", "totp", "mfa", "pin", "tan", "cvv", "cvc",
            "ccv", "cardno", "creditcard", "iban", "privatekey", "sessionid");

    /**
     * What a value looks like once it was located but not extracted: {@code ‹redacted›}. The single
     * spelling of that marker matters, because three places <em>compare</em> against it rather than
     * merely print it - the fill journal decides there that a value is unreadable, the field model
     * refuses to count it as an observation of the field, and {@code bridge.js} writes it into the
     * snapshot. The bridge cannot import this constant (it runs in the page, before any Java is
     * involved), so the equality of the two spellings is held by
     * {@code BrowserBridgeContractTest.theBridgeMasksWithTheSameMarkerAsTheServer}.
     */
    public static final String MASK = "‹redacted›";

    /**
     * Field names in which a digest <em>acts like a password</em>: client-side hashed logins, JWTs,
     * signatures. The restriction to such names is intentional - a {@code "checksum"} or an
     * {@code "id"} with 40 hex characters is not a secret, and blindly masked value fields would take
     * from the LLM the data it is here for.
     */
    private static final List<String> DIGEST_KEYS = List.of(
            "hash", "digest", "signature", "signatur", "mac", "salt", "jwt", "bearer", "credential",
            "thumbprint", "fingerprint");

    /**
     * {@code "name": value} in JSON. Every group has a name - the temptation to index numerically is
     * expensive here: the prefix group contains the key group, and one would read the field name as
     * the value (the error the digest case survived for two weeks, because the password case caught it
     * over the <em>name</em> anyway).
     */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "(?<prefix>\"(?<key>[^\"]{1,80})\"\\s*:\\s*)"
                    + "(?<value>\"(?:\\\\.|[^\"\\\\])*\"|true|false|null|-?\\d+(?:\\.\\d+)?"
                    + "|\\{[^{}]*\\}|\\[[^\\[\\]]*\\])");

    private static final Pattern FORM_PAIR = Pattern.compile(
            "(?<=[&?;]|^)(?<key>[A-Za-z0-9_.\\-\\[\\]]{1,80})=(?<value>[^&;\\s]*)");

    private final Set<String> keys;

    /**
     * @param extraKeys additional field names from the application's registry entry
     */
    public BrowserSecrets(Collection<String> extraKeys) {
        keys = new LinkedHashSet<>();
        DEFAULT_KEYS.forEach(key -> keys.add(normalize(key)));
        if (extraKeys != null) {
            extraKeys.forEach(key -> keys.add(normalize(key)));
        }
    }

    /**
     * Comparable form of a field name: lowercase, without separators.
     *
     * @param name name from HTML, JSON or a form
     * @return the key used by {@link #isKey(String)}
     */
    public static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * @param name field or header name
     * @return true when the matching value may not stand in the capture
     */
    public boolean isKey(String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return false;
        }
        if (keys.contains(key)) {
            return true;
        }
        for (String secret : keys) {
            // "userpassword", "login_pwd", "acct_iban": the names in real applications are composed,
            // which is why there are partial patterns and not an exact list.
            if (key.length() >= 4 && key.contains(secret)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param value suspicious string (e.g. a field value)
     * @return true when the value looks like a hash/digest and therefore acts like a password
     */
    public static boolean digestLike(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.length() >= 32 && trimmed.length() <= 128
                && trimmed.matches("(?i)[a-f0-9$+/=.:-]{32,128}");
    }

    /**
     * Replaces the values of secret-suspicious fields in JSON or form text.
     *
     * @param body raw text (JSON, {@code application/x-www-form-urlencoded})
     * @return the redacted text and whether anything was replaced
     */
    public Redaction redactStructured(String body) {
        if (body == null || body.isBlank()) {
            return new Redaction("", false);
        }
        StringBuilder out = new StringBuilder(body.length() + 16);
        Matcher json = JSON_PAIR.matcher(body);
        int last = 0;
        int replaced = 0;
        while (json.find()) {
            String key = unquote(json.group("key"));
            String value = unquote(json.group("value"));
            if (!isKey(key) && !(digestLike(value) && looksLikeCredential(key))) {
                continue;
            }
            out.append(body, last, json.start()).append(json.group("prefix"))
                    .append('"').append(MASK).append('"');
            last = json.end();
            replaced++;
        }
        String text;
        if (replaced == 0) {
            text = body;
        } else {
            out.append(body, last, body.length());
            text = out.toString();
        }
        if (text.contains("=") && !text.startsWith("{") && !text.startsWith("[")) {
            // Forms: key=value pairs, otherwise a login POST looks like text without fields.
            Matcher form = FORM_PAIR.matcher(text);
            StringBuilder formOut = new StringBuilder(text.length() + 16);
            int formLast = 0;
            int formReplaced = 0;
            while (form.find()) {
                String key = form.group("key");
                String value = form.group("value");
                if (!isKey(key) && !(digestLike(value) && looksLikeCredential(key))) {
                    continue;
                }
                formOut.append(text, formLast, form.start()).append(key).append('=')
                        .append(MASK).append('(').append(value.length()).append(')');
                formLast = form.end();
                formReplaced++;
            }
            if (formReplaced > 0) {
                formOut.append(text, formLast, text.length());
                text = formOut.toString();
                replaced += formReplaced;
            }
        }
        return new Redaction(text, replaced > 0);
    }

    /**
     * Collects headers into a harmless description: name, count, but no values.
     *
     * @param headers the headers of one direction
     * @return one line per header, secrets only as counters
     */
    public String summarizeHeaders(Map<String, List<String>> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            if (isKey(name) || isTransportSecret(name)) {
                sb.append(name).append(": ‹").append(values.size()).append(" value(s) redacted");
                if (!values.isEmpty()) {
                    sb.append(", ").append(values.get(0).length()).append(" characters");
                }
                sb.append("›\n");
            } else {
                String joined = String.join(", ", values);
                sb.append(name).append(": ")
                        .append(joined.length() > 160 ? joined.substring(0, 160) + "…" : joined)
                        .append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Headers that may never appear as a value, even when their name gives nothing away.
     *
     * @param name header name
     * @return true for cookie/auth transport
     */
    public static boolean isTransportSecret(String name) {
        String key = normalize(name);
        return key.equals("cookie") || key.equals("setcookie") || key.equals("authorization")
                || key.equals("proxyauthorization") || key.equals("xauthtoken");
    }

    /**
     * @param name field name
     * @return true when the name points at a credential artifact (hash, signature, token)
     */
    public boolean looksLikeCredential(String name) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return false;
        }
        for (String marker : DIGEST_KEYS) {
            if (key.contains(marker)) {
                return true;
            }
        }
        return isKey(name);
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value;
        }
        char first = value.charAt(0);
        if (first != '"' && first != '\'') {
            return value;
        }
        int end = value.lastIndexOf(first);
        return end > 0 ? value.substring(1, end) : value.substring(1);
    }

    /**
     * Result of one redaction stage.
     *
     * @param text     the rotated text
     * @param redacted true when at least one value was replaced
     */
    public record Redaction(String text, boolean redacted) {
    }
}
