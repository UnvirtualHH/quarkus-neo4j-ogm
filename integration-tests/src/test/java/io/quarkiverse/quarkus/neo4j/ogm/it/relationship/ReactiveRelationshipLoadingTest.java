package io.quarkiverse.quarkus.neo4j.ogm.it.relationship;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;

import io.quarkiverse.quarkus.neo4j.ogm.it.model.Author;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.AuthorBaseReactiveRepository;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.Book;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.BookBaseReactiveRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ReactiveRelationshipLoadingTest {

    @Inject
    Driver driver;

    @Inject
    AuthorBaseReactiveRepository authorRepository;

    @Inject
    BookBaseReactiveRepository bookRepository;

    @BeforeEach
    public void clearDatabase() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
        }
    }

    @Test
    public void testLoadAuthorWithBooks() {
        // Given
        String authorId = createTestAuthorWithBooks();

        // When
        Author author = authorRepository.findById(authorId).await().indefinitely();

        // Then
        assertNotNull(author);
        assertNotNull(author.getBooks());
        assertEquals(2, author.getBooks().size());
        assertTrue(author.getBooks().stream().anyMatch(b -> "Book 1".equals(b.getTitle())));
        assertTrue(author.getBooks().stream().anyMatch(b -> "Book 2".equals(b.getTitle())));
    }

    @Test
    public void testLoadBookWithAuthor() {
        // Given
        String authorId = createTestAuthorWithBooks();

        // When
        Book book = bookRepository.querySingle("MATCH (b:Book {title: $title}) RETURN b AS node",
                Values.parameters("title", "Book 1").asMap())
                .await().indefinitely();

        // Then
        assertNotNull(book);
        assertNotNull(book.getAuthor());
        assertEquals(UUID.fromString(authorId), book.getAuthor().getId());
    }

    private String createTestAuthorWithBooks() {
        try (Session session = driver.session()) {
            UUID authorId = UUID.randomUUID();
            UUID bookId1 = UUID.randomUUID();
            UUID bookId2 = UUID.randomUUID();
            // Create test data
            String cypher = "CREATE (a:Author {id: '" + authorId.toString() + "', name: 'Test Author'}) " +
                    "CREATE (b1:Book {id: '" + bookId1.toString() + "', title: 'Book 1'}) " +
                    "CREATE (b2:Book {id: '" + bookId2.toString() + "', title: 'Book 2'}) " +
                    "CREATE (a)-[:WROTE]->(b1) " +
                    "CREATE (a)-[:WROTE]->(b2) " +
                    "RETURN a.id as id";

            return session.run(cypher).single().get("id").asString();
        }
    }
}
