package de.prgrm.quarkus.neo4j.ogm.it.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.prgrm.quarkus.neo4j.ogm.it.model.Person;
import de.prgrm.quarkus.neo4j.ogm.it.model.PersonBaseRepository;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Pageable;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Paged;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Sort;
import de.prgrm.quarkus.neo4j.ogm.runtime.repository.util.Sortable;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class PaginationTest {

    @Inject
    PersonBaseRepository personRepository;

    @BeforeEach
    void setup() {
        // Create 25 test persons
        for (int i = 1; i <= 25; i++) {
            Person p = new Person();
            p.setName("Person " + String.format("%02d", i));
            personRepository.create(p);
        }
    }

    @AfterEach
    void cleanup() {
        // Delete all persons by finding all IDs first
        List<Person> allPersons = personRepository.findAll();
        if (!allPersons.isEmpty()) {
            List<Object> ids = allPersons.stream().map(p -> (Object) p.getId()).toList();
            personRepository.deleteAllByIds(ids);
        }
    }

    @Test
    void testBasicPagination() {
        Pageable pageable = new Pageable(0, 10);
        List<Person> page1 = personRepository.findAll(pageable, null);

        assertEquals(10, page1.size());
    }

    @Test
    void testPaginationWithSorting() {
        Pageable pageable = new Pageable(0, 10);
        Sortable sortable = new Sortable(List.of(Sort.asc("name")));

        List<Person> page = personRepository.findAll(pageable, sortable);

        assertEquals(10, page.size());
        assertEquals("Person 01", page.get(0).getName());
    }

    @Test
    void testPaginationSecondPage() {
        Pageable pageable = new Pageable(1, 10);
        List<Person> page2 = personRepository.findAll(pageable, null);

        assertEquals(10, page2.size());
    }

    @Test
    void testPaginationLastPage() {
        Pageable pageable = new Pageable(2, 10);
        List<Person> page3 = personRepository.findAll(pageable, null);

        assertEquals(5, page3.size());
    }

    @Test
    void testPagedWithTotalCount() {
        Pageable pageable = new Pageable(0, 10);
        Paged<Person> paged = personRepository.findAllPaged(pageable, null);

        assertNotNull(paged);
        assertEquals(25, paged.totalElements());
        assertEquals(10, paged.content().size());
    }

    @Test
    void testSortingDescending() {
        Sortable sortable = new Sortable(List.of(Sort.desc("name")));
        Pageable pageable = new Pageable(0, 5);

        List<Person> page = personRepository.findAll(pageable, sortable);

        assertEquals(5, page.size());
        assertTrue(page.get(0).getName().compareTo(page.get(4).getName()) > 0);
    }

    @Test
    void testEmptyPageBeyondResults() {
        Pageable pageable = new Pageable(10, 10);
        List<Person> page = personRepository.findAll(pageable, null);

        assertTrue(page.isEmpty());
    }
}
