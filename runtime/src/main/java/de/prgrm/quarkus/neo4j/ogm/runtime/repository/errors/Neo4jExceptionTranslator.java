package de.prgrm.quarkus.neo4j.ogm.runtime.repository.errors;

import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.TransientException;

public final class Neo4jExceptionTranslator {

    public static RepositoryException translate(Throwable e, String context) {
        if (e instanceof Neo4jException neo4jEx) {
            return translateFromNeo4jException(neo4jEx, context);
        }

        if (e.getCause() instanceof Neo4jException causeEx) {
            return translateFromNeo4jException(causeEx, context + " (wrapped)");
        }

        if (e.getMessage() != null && e.getMessage().contains("Neo.ClientError.")) {
            String msg = e.getMessage();
            String code = extractNeo4jCode(msg);

            if (code.contains("ConstraintValidationFailed")
                    || code.contains("ConstraintAlreadyExists")
                    || code.contains("ConstraintWithNameAlreadyExists")) {
                return new ConstraintViolationRepositoryException(
                        buildMessage(context, msg, code), e);
            }

            if (code.contains("SyntaxError") || code.contains("ParameterMissing")) {
                return new RepositoryException(buildMessage(context, msg, code), e);
            }

            if (code.contains("Security.Unauthorized")) {
                return new RepositoryException("Unauthorized: " + buildMessage(context, msg, code), e);
            }
        }

        return new RepositoryException(buildMessage(context, e.getMessage(), null), e);
    }

    private static RepositoryException translateFromNeo4jException(Neo4jException e, String context) {
        String code = safe(e.code());
        String message = buildMessage(context, e.getMessage(), code);

        if (e instanceof TransientException) {
            return new TransientRepositoryException(message, e);
        }

        if (e instanceof ClientException) {
            switch (code) {
                case "Neo.ClientError.Schema.ConstraintValidationFailed",
                        "Neo.ClientError.Schema.ConstraintAlreadyExists",
                        "Neo.ClientError.Schema.ConstraintWithNameAlreadyExists" -> {
                    return new ConstraintViolationRepositoryException(message, e);
                }
                case "Neo.ClientError.Statement.SyntaxError",
                        "Neo.ClientError.Statement.ParameterMissing" -> {
                    return new RepositoryException(message, e);
                }
                case "Neo.ClientError.Security.Unauthorized" -> {
                    return new RepositoryException("Unauthorized: " + message, e);
                }
            }
        }

        return new RepositoryException(message, e);
    }

    private static String buildMessage(String context, String message, String code) {
        return (context == null ? "" : context + " -> ") + safe(message)
                + (code == null ? "" : " (code=" + code + ")");
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
