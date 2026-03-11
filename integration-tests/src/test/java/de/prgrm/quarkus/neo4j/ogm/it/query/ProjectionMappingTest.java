package de.prgrm.quarkus.neo4j.ogm.it.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import de.prgrm.quarkus.neo4j.ogm.it.model.BookBaseRepository;
import de.prgrm.quarkus.neo4j.ogm.it.model.BookSummary;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ProjectionMappingTest {

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
    void testSingleProjectionReturnsBookSummary() {
        try (Session session = driver.session()) {
            session.run("CREATE (b:Book {id: '" + UUID.randomUUID() + "', title: 'Test Book', active: true})");
        }

        BookSummary summary = bookRepository.getBookSummary("Test Book");

        assertNotNull(summary, "Projection should not be null");
        assertEquals("Test Book", summary.title());
        assertTrue(summary.active());
    }

    @Test
    void testSingleProjectionWithInactiveBook() {
        try (Session session = driver.session()) {
            session.run("CREATE (b:Book {id: '" + UUID.randomUUID() + "', title: 'Inactive Book', active: false})");
        }

        BookSummary summary = bookRepository.getBookSummary("Inactive Book");

        assertNotNull(summary);
        assertEquals("Inactive Book", summary.title());
        assertFalse(summary.active());
    }

    @Test
    void testSingleProjectionReturnsNullForMissingBook() {
        BookSummary summary = bookRepository.getBookSummary("Nonexistent");

        assertNull(summary, "Should return null when no book is found");
    }

    @Test
    void testListProjectionReturnsAllBooks() {
        try (Session session = driver.session()) {
            session.run("CREATE (b1:Book {id: '" + UUID.randomUUID() + "', title: 'Book A', active: true})");
            session.run("CREATE (b2:Book {id: '" + UUID.randomUUID() + "', title: 'Book B', active: false})");
            session.run("CREATE (b3:Book {id: '" + UUID.randomUUID() + "', title: 'Book C', active: true})");
        }

        List<BookSummary> summaries = bookRepository.getAllBookSummaries();

        assertNotNull(summaries);
        assertEquals(3, summaries.size(), "Should return all 3 books");

        List<String> titles = summaries.stream().map(BookSummary::title).sorted().toList();
        assertEquals(List.of("Book A", "Book B", "Book C"), titles);

        // Verify active states are mapped correctly
        long activeCount = summaries.stream().filter(BookSummary::active).count();
        assertEquals(2, activeCount, "2 books should be active");
    }

    @Test
    void testListProjectionReturnsEmptyListForNoBooks() {
        List<BookSummary> summaries = bookRepository.getAllBookSummaries();

        assertNotNull(summaries, "Should return empty list, not null");
        assertTrue(summaries.isEmpty());
    }
}
