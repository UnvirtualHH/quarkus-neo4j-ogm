package de.prgrm.quarkus.neo4j.ogm.runtime.exception;

public class RepositoryException extends RuntimeException {
    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}