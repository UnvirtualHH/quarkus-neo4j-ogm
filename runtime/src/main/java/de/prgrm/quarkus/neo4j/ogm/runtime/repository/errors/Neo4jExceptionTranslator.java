package de.prgrm.quarkus.neo4j.ogm.runtime.repository.errors;

import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.TransientException;

/**
 * Translates Neo4j driver exceptions into the library's {@link RepositoryException} hierarchy.
 *
 * <p>
 * The raw driver message can contain fragments of the executed Cypher and parameter values. To avoid
 * leaking that into a response if the application surfaces {@link Throwable#getMessage()} to clients,
 * the translated exception message only carries the operation context and the stable Neo4j error code.
 * The full original exception (including the driver message) is preserved as the {@code cause} and is
 * therefore still available in logs and stack traces.
 */
public final class Neo4jExceptionTranslator {

    private static final String CONSTRAINT_VALIDATION_FAILED = "Neo.ClientError.Schema.ConstraintValidationFailed";
    private static final String CONSTRAINT_ALREADY_EXISTS = "Neo.ClientError.Schema.ConstraintAlreadyExists";
    private static final String CONSTRAINT_WITH_NAME_EXISTS = "Neo.ClientError.Schema.ConstraintWithNameAlreadyExists";
    private static final String SYNTAX_ERROR = "Neo.ClientError.Statement.SyntaxError";
    private static final String PARAMETER_MISSING = "Neo.ClientError.Statement.ParameterMissing";
    private static final String SECURITY_UNAUTHORIZED = "Neo.ClientError.Security.Unauthorized";

    public static RepositoryException translate(Throwable e, String context) {
        // Try to extract Neo4jException from the exception or its cause using class hierarchy
        Neo4jException neo4jEx = extractNeo4jException(e);
        if (neo4jEx != null) {
            return translateFromNeo4jException(neo4jEx, context);
        }

        // Check cause as well
        Throwable cause = e.getCause();
        if (cause != null) {
            Neo4jException causedNeo4jEx = extractNeo4jException(cause);
            if (causedNeo4jEx != null) {
                return translateFromNeo4jException(causedNeo4jEx, context + " (wrapped)");
            }
        }

        // Fallback: try to parse error code from message
        if (e.getMessage() != null && e.getMessage().contains("Neo.ClientError.")) {
            String code = extractNeo4jCode(e.getMessage());
            return translateFromCode(code, buildMessage(context, code), e);
        }

        return new RepositoryException(buildMessage(context, null), e);
    }

    private static Neo4jException extractNeo4jException(Throwable e) {
        // Use class hierarchy check instead of instanceof
        Class<?> exClass = e.getClass();
        if (Neo4jException.class.isAssignableFrom(exClass)) {
            return (Neo4jException) e;
        }
        return null;
    }

    private static RepositoryException translateFromNeo4jException(Neo4jException e, String context) {
        String code = safe(e.code());
        String message = buildMessage(context, code);

        // Check if transient by class hierarchy (not instanceof)
        Class<?> exClass = e.getClass();
        if (TransientException.class.isAssignableFrom(exClass)) {
            return new TransientRepositoryException(message, e);
        }

        // Check if client exception and translate by code
        if (ClientException.class.isAssignableFrom(exClass)) {
            return translateFromCode(code, message, e);
        }

        return new RepositoryException(message, e);
    }

    private static RepositoryException translateFromCode(String code, String message, Throwable e) {
        if (code == null || code.isEmpty()) {
            return new RepositoryException(message, e);
        }

        // Constraint violations
        if (code.equals(CONSTRAINT_VALIDATION_FAILED)
                || code.equals(CONSTRAINT_ALREADY_EXISTS)
                || code.equals(CONSTRAINT_WITH_NAME_EXISTS)
                || code.contains("ConstraintValidationFailed")
                || code.contains("ConstraintAlreadyExists")
                || code.contains("ConstraintWithNameAlreadyExists")) {
            return new ConstraintViolationRepositoryException(message, e);
        }

        // Syntax/parameter errors
        if (code.equals(SYNTAX_ERROR) || code.equals(PARAMETER_MISSING)
                || code.contains("SyntaxError") || code.contains("ParameterMissing")) {
            return new RepositoryException(message, e);
        }

        // Security errors
        if (code.equals(SECURITY_UNAUTHORIZED) || code.contains("Security.Unauthorized")) {
            return new RepositoryException("Unauthorized: " + message, e);
        }

        return new RepositoryException(message, e);
    }

    private static String buildMessage(String context, String code) {
        // Intentionally omit the raw driver message (may contain Cypher/parameter values);
        // it is retained on the exception cause for logging.
        String base = (context == null || context.isBlank()) ? "Neo4j operation failed" : context;
        return base + (code == null || code.isEmpty() ? "" : " (code=" + code + ")");
    }

    private static String extractNeo4jCode(String message) {
        int idx = message.indexOf("Neo.ClientError.");
        if (idx >= 0) {
            int end = message.indexOf(' ', idx);
            if (end < 0)
                end = message.length();
            return message.substring(idx, end).trim();
        }
        return "";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private Neo4jExceptionTranslator() {
    }
}
