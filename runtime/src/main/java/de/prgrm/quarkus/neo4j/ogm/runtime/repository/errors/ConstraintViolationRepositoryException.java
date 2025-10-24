package de.prgrm.quarkus.neo4j.ogm.runtime.repository.errors;

public class ConstraintViolationRepositoryException extends RepositoryException {
    public ConstraintViolationRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}