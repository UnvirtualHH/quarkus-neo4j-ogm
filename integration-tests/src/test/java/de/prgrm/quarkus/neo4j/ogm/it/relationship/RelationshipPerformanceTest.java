package de.prgrm.quarkus.neo4j.ogm.it.relationship;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import de.prgrm.quarkus.neo4j.ogm.it.model.Author;
import de.prgrm.quarkus.neo4j.ogm.it.model.AuthorBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@Tag("performance")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class RelationshipPerformanceTest {

    private static final String PERF_ENABLED_PROPERTY = "it.performance";
    private static final String PERF_BOOKS_PROPERTY = "it.performance.books";
    private static final int DEFAULT_BOOK_COUNT = 2000;

    @Inject
    Driver driver;

    @Inject
    AuthorBaseRepository authorRepository;

    private String authorId;
    private int bookCount;

    @BeforeAll
    void setup() {
        assumeTrue(Boolean.getBoolean(PERF_ENABLED_PROPERTY),
                "Performance tests disabled. Run with -D" + PERF_ENABLED_PROPERTY + "=true");

        bookCount = Integer.parseInt(System.getProperty(PERF_BOOKS_PROPERTY, String.valueOf(DEFAULT_BOOK_COUNT)));
        authorId = createAuthorWithBooks(bookCount);
    }

    @Test
    @Timeout(value = 2, unit = java.util.concurrent.TimeUnit.MINUTES)
    void loadAuthorWithManyBooks() {
        long start = System.nanoTime();
        Author author = authorRepository.findById(authorId);
        long durationNanos = System.nanoTime() - start;

        assertNotNull(author);
        assertNotNull(author.getBooks());
        assertEquals(bookCount, author.getBooks().size());

        System.out.println("Loaded author with " + bookCount + " books in " + Duration.ofNanos(durationNanos));
    }

    private String createAuthorWithBooks(int booksToCreate) {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");

            String authorId = UUID.randomUUID().toString();
            List<Map<String, Object>> books = IntStream.rangeClosed(1, booksToCreate)
                    .mapToObj(i -> Map.<String, Object> of(
                            "id", UUID.randomUUID().toString(),
                            "title", "Perf Book " + i))
                    .toList();

            String cypher = "CREATE (a:Author {id: $authorId, name: $name}) " +
                    "WITH a " +
                    "UNWIND $books AS b " +
                    "CREATE (book:Book {id: b.id, title: b.title}) " +
                    "CREATE (a)-[:WROTE]->(book)";

            session.run(cypher, Map.of(
                    "authorId", authorId,
                    "name", "Perf Author",
                    "books", books));

            return authorId;
        }
    }
}
