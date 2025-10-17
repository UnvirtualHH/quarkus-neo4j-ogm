package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.List;
import java.util.UUID;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.*;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository.RepositoryType;

@NodeEntity
@GenerateRepository(RepositoryType.BOTH)
public class Author {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;
    private String name;

    @Relationship(type = "WROTE", direction = Direction.OUTGOING, mode = RelationshipMode.FETCH_ONLY)
    private List<Book> books;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Book> getBooks() {
        return books;
    }

    public void setBooks(List<Book> books) {
        this.books = books;
    }
}
