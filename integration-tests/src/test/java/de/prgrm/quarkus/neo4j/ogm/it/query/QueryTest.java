package de.prgrm.quarkus.neo4j.ogm.it.query;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import de.prgrm.quarkus.neo4j.ogm.it.model.Book;
import de.prgrm.quarkus.neo4j.ogm.it.model.BookBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class QueryTest {

    @Inject
    Driver driver;

    @Inject
    BookBaseRepository bookRepository;

    @BeforeEach
    public void clearDatabase() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
        }
    }

    @Test
    void testBookExists() {
        String authorId = createTestAuthorWithBooks();

        Book byTitle = bookRepository.findByTitle("Book 1");

        assertNotNull(byTitle);
    }

    @Test
    void testChangeBookExists() {
        String authorId = createTestAuthorWithBooks();

        Book book = bookRepository.touchAndReturn("Book 1");

        assertNotNull(book);
    }

    private String createTestAuthorWithBooks() {
        try (Session session = driver.session()) {
            UUID authorId = UUID.randomUUID();
            UUID bookId1 = UUID.randomUUID();
            UUID bookId2 = UUID.randomUUID();
            // Create test data
            String cypher = "CREATE (a:Author {id: '" + authorId.toString() + "', name: 'Test Author'}) " +
                    "CREATE (b1:Book {id: '" + bookId1.toString() + "', title: 'Book 1', active: false}) " +
                    "CREATE (b2:Book {id: '" + bookId2.toString() + "', title: 'Book 2', active: false}) " +
                    "CREATE (a)-[:WROTE]->(b1) " +
                    "CREATE (a)-[:WROTE]->(b2) " +
                    "RETURN a.id as id";

            return session.run(cypher).single().get("id").asString();
        }
    }
}
