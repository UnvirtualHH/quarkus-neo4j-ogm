package de.prgrm.quarkus.neo4j.ogm.runtime.repository.util;

import java.util.regex.Pattern;

/**
 * Validation helpers for Cypher identifiers (property names, labels, relationship types).
 *
 * <p>
 * Property names, labels and relationship types cannot be passed as query parameters in Cypher –
 * they have to be concatenated into the query string. Whenever such a value can originate from
 * outside the application (e.g. a {@code sort} or {@code filter} request parameter that is forwarded
 * into {@link Sort}/{@link Filter}), unvalidated concatenation allows Cypher injection.
 *
 * <p>
 * This class enforces a strict allow-list pattern. Identifiers that do not match are rejected with an
 * {@link IllegalArgumentException} instead of being silently escaped, so callers fail fast on
 * unexpected input.
 */
public final class CypherIdentifier {

    /**
     * Matches a single, unquoted Cypher identifier: a letter or underscore followed by letters,
     * digits or underscores. This intentionally rejects whitespace, dots, backticks, braces and any
     * other character that could be used to break out of the surrounding clause.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private CypherIdentifier() {
    }

    /**
     * Validates a property name used in WHERE/ORDER BY clauses.
     *
     * @param property the property name (must not be null/blank and must be a safe identifier)
     * @return the validated property name
     * @throws IllegalArgumentException if the property name is null, blank or contains unsafe characters
     */
    public static String requireValidProperty(String property) {
        return require(property, "property name");
    }

    /**
     * Validates a node label or relationship type used in MATCH/MERGE clauses.
     *
     * @param identifier the label or relationship type
     * @return the validated identifier
     * @throws IllegalArgumentException if the identifier is null, blank or contains unsafe characters
     */
    public static String requireValidIdentifier(String identifier) {
        return require(identifier, "identifier");
    }

    private static String require(String value, String kind) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cypher " + kind + " must not be null or blank");
        }
        if (!SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Illegal Cypher " + kind + " '" + value
                            + "': only letters, digits and underscores are allowed (must start with a letter or underscore)");
        }
        return value;
    }
}
