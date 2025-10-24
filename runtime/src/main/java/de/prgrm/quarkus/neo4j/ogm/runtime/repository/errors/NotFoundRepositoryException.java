package de.prgrm.quarkus.neo4j.ogm.runtime.repository.errors;

public class NotFoundRepositoryException extends RepositoryException {
    public NotFoundRepositoryException(String message) {
        super(message);
    }
}