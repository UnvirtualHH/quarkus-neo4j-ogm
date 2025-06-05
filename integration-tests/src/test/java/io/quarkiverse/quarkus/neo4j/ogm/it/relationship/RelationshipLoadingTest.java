package io.quarkiverse.quarkus.neo4j.ogm.it.relationship;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import io.quarkiverse.quarkus.neo4j.ogm.it.model.Author;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.AuthorBaseRepository;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.Book;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.BookBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class RelationshipLoadingTest {

    @Inject
    Driver driver;

    @Inject
    AuthorBaseRepository authorRepository;

    @Inject
    BookBaseRepository bookRepository;

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
        Author author = authorRepository.findById(authorId);

        // Then
        assertNotNull(author);
        assertNotNull(author.getBooks());
        assertFalse(author.getBooks().isEmpty());
        assertEquals(2, author.getBooks().size());
        assertTrue(author.getBooks().stream().anyMatch(b -> "Book 1".equals(b.getTitle())));
        assertTrue(author.getBooks().stream().anyMatch(b -> "Book 2".equals(b.getTitle())));
    }

    @Test
    public void testLoadBookWithAuthor() {
        // Given
        String authorId = createTestAuthorWithBooks();

        // When
        Book book = bookRepository.findByTitle("Book 1");

        // Then
        assertNotNull(book.getAuthor());
        assertEquals(authorId, book.getAuthor().getId());
    }

    private String createTestAuthorWithBooks() {
        try (Session session = driver.session()) {
            // Create test data
            String cypher = "CREATE (a:Author {id: 'a1', name: 'Test Author'}) " +
                    "CREATE (b1:Book {id: 'b1', title: 'Book 1'}) " +
                    "CREATE (b2:Book {id: 'b2', title: 'Book 2'}) " +
                    "CREATE (a)-[:WROTE]->(b1) " +
                    "CREATE (a)-[:WROTE]->(b2) " +
                    "RETURN a.id as id";

            return session.run(cypher).single().get("id").asString();
        }
    }
}
