package de.prgrm.quarkus.neo4j.ogm.it.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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
public class DesignOnlyMappingTest {

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
    void testDesignOnlyRelationshipMappedFromCustomQuery() {
        // Create books with RELATED_TO relationships
        try (Session session = driver.session()) {
            UUID mainId = UUID.randomUUID();
            UUID related1Id = UUID.randomUUID();
            UUID related2Id = UUID.randomUUID();
            session.run(
                    "CREATE (b:Book {id: '" + mainId + "', title: 'Main Book', active: false}) " +
                            "CREATE (r1:Book {id: '" + related1Id + "', title: 'Related 1', active: false}) " +
                            "CREATE (r2:Book {id: '" + related2Id + "', title: 'Related 2', active: false}) " +
                            "CREATE (b)-[:RELATED_TO]->(r1) " +
                            "CREATE (b)-[:RELATED_TO]->(r2)");
        }

        // Use custom query that returns DESIGN_ONLY relationship data
        Book result = bookRepository.findWithRelatedBooks("Main Book");

        assertNotNull(result, "Main book should be found");
        assertEquals("Main Book", result.getTitle());
        assertNotNull(result.getRelatedBooks(), "DESIGN_ONLY relatedBooks should be populated from query result");
        assertEquals(2, result.getRelatedBooks().size(), "Should have 2 related books");

        List<String> relatedTitles = result.getRelatedBooks().stream()
                .map(Book::getTitle)
                .sorted()
                .toList();
        assertEquals(List.of("Related 1", "Related 2"), relatedTitles);
    }

    @Test
    void testDesignOnlyRelationshipEmptyWhenNoRelations() {
        // Create a book without RELATED_TO relationships
        try (Session session = driver.session()) {
            UUID bookId = UUID.randomUUID();
            session.run("CREATE (b:Book {id: '" + bookId + "', title: 'Lonely Book', active: false})");
        }

        Book result = bookRepository.findWithRelatedBooks("Lonely Book");

        assertNotNull(result);
        assertEquals("Lonely Book", result.getTitle());
        assertNotNull(result.getRelatedBooks(), "relatedBooks should be an empty list, not null");
        assertTrue(result.getRelatedBooks().isEmpty(), "Should have no related books");
    }

    @Test
    void testDesignOnlyNotAutoFetched() {
        // Create a book with RELATED_TO relationships
        try (Session session = driver.session()) {
            UUID mainId = UUID.randomUUID();
            UUID relatedId = UUID.randomUUID();
            session.run(
                    "CREATE (b:Book {id: '" + mainId + "', title: 'Auto Test', active: false}) " +
                            "CREATE (r:Book {id: '" + relatedId + "', title: 'Should Not Load', active: false}) " +
                            "CREATE (b)-[:RELATED_TO]->(r)");
        }

        // Use standard findByTitle which does NOT return relatedBooks column
        Book result = bookRepository.findByTitle("Auto Test");

        assertNotNull(result);
        assertEquals("Auto Test", result.getTitle());
        // DESIGN_ONLY relationships should NOT be auto-fetched
        assertNull(result.getRelatedBooks(),
                "DESIGN_ONLY relatedBooks should NOT be auto-fetched by standard queries");
    }

    @Test
    void testDuplicateQueryParametersWork() {
        // Tests fix for issue #56: duplicate $title parameter in query
        try (Session session = driver.session()) {
            UUID bookId = UUID.randomUUID();
            session.run("CREATE (b:Book {id: '" + bookId + "', title: 'Duplicate Test', active: false})");
        }

        Book result = bookRepository.findByTitleDuplicate("Duplicate Test");

        assertNotNull(result, "Query with duplicate $title parameter should work");
        assertEquals("Duplicate Test", result.getTitle());
    }
}
