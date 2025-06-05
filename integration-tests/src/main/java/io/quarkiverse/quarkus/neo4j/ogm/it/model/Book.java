package io.quarkiverse.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Direction;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository.RepositoryType;

@NodeEntity
@GenerateRepository(RepositoryType.BOTH)
@Queries({
        @Query(name = "findByTitle", cypher = "MATCH (b:Book {title: $title}) RETURN b AS node")
})
public class Book {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;
    private String title;

    @Relationship(type = "WROTE", direction = Direction.INCOMING)
    private Author author;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Author getAuthor() {
        return author;
    }

    public void setAuthor(Author author) {
        this.author = author;
    }
}
