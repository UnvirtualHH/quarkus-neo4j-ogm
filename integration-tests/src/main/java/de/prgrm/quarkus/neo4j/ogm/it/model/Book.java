package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.List;
import java.util.UUID;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.ReturnType;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.*;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository.RepositoryType;

@NodeEntity
@GenerateRepository(RepositoryType.BOTH)
@Queries({
        @Query(name = "findByTitle", cypher = "MATCH (b:Book {title: $title}) RETURN b"),
        @Query(name = "deleteAll", cypher = "MATCH (n:Book) DETACH DELETE n"),
        @Query(name = "touchAndReturn", cypher = "MATCH (b:Book {title: $title}) SET b.active = true RETURN b AS blah"),
        @Query(name = "findWithRelatedBooks", cypher = """
                MATCH (b:Book {title: $title})
                OPTIONAL MATCH (b)-[:RELATED_TO]->(r:Book)
                RETURN b, collect(r) as relatedBooks
                """),
        @Query(name = "findByTitleDuplicate", cypher = """
                MATCH (b:Book)
                WHERE b.title = $title OR b.title STARTS WITH $title
                RETURN b
                """)
})
public class Book {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;
    private String title;

    @Relationship(type = "WROTE", direction = Direction.INCOMING, mode = RelationshipMode.FETCH_AND_PERSIST)
    private Author author;

    @Relationship(type = "RELATED_TO", direction = Direction.OUTGOING, mode = RelationshipMode.DESIGN_ONLY)
    private List<Book> relatedBooks;

    @Property(name = "active")
    private boolean active;

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

    public List<Book> getRelatedBooks() {
        return relatedBooks;
    }

    public void setRelatedBooks(List<Book> relatedBooks) {
        this.relatedBooks = relatedBooks;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
