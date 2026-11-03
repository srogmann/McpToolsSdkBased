package org.rogmann.mcp2sdk.git.forge;

import java.util.regex.Pattern;

/**
 * A repository on the configured server: {@code owner/name}, and nothing else.
 *
 * <p>
 * The grammar is the first half of the SSRF guard: a caller names a repository, never a host, a
 * scheme, a query or a path. The second half is {@link ForgeConfig}, which owns the server and the
 * owner allowlist - so a prompt injection cannot address anything the operator did not configure.
 * </p>
 *
 * @param owner the user or organisation
 * @param name  the repository name
 */
public record ForgeRepo(String owner, String name) {

    /** One path segment of a repository name. */
    private static final Pattern PART = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    /**
     * Parses {@code owner/name}.
     *
     * @param text the repository as the caller named it
     * @return the repository
     * @throws IllegalArgumentException if the text is not {@code owner/name}, a part contains a
     *         character outside the grammar, or the owner is not in the configured allowlist
     */
    public static ForgeRepo parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A repository is named 'owner/name' - see "
                    + "git.help()");
        }
        String value = text.strip();
        String[] parts = value.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("A repository is named 'owner/name' (exactly one "
                    + "'/'), but was: '" + value + "'");
        }
        for (String part : parts) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("A repository part must not be '" + part
                        + "': '" + value + "'");
            }
            if (!PART.matcher(part).matches()) {
                throw new IllegalArgumentException("A repository part may only contain letters, "
                        + "digits, '.', '_' and '-', but was: '" + part + "' (in '" + value + "')");
            }
        }
        if (!ForgeConfig.ownerAllowed(parts[0])) {
            throw new IllegalArgumentException("The owner '" + parts[0] + "' is not allowed by "
                    + "this installation (" + ForgeConfig.PROP_ORGS + "="
                    + String.join(",", ForgeConfig.orgs()) + ").");
        }
        return new ForgeRepo(parts[0], parts[1]);
    }

    /**
     * @return the API path of this repository, e.g. {@code /repos/owner/name}
     */
    public String apiPath() {
        return "/repos/" + owner + "/" + name;
    }

    /**
     * @return {@code owner/name}
     */
    public String fullName() {
        return owner + "/" + name;
    }

    /**
     * @return the web address of this repository on the configured server
     */
    public String htmlUrl() {
        return ForgeConfig.serverUrl() + "/" + owner + "/" + name;
    }
}
