package de.prgrm.quarkus.neo4j.ogm.runtime.repository.errors;

public class TransientRepositoryException extends RepositoryException {
    public TransientRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
