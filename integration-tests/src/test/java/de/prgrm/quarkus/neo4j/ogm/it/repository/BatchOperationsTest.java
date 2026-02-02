package de.prgrm.quarkus.neo4j.ogm.it.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import de.prgrm.quarkus.neo4j.ogm.it.model.Person;
import de.prgrm.quarkus.neo4j.ogm.it.model.PersonBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class BatchOperationsTest {

    @Inject
    PersonBaseRepository personRepository;

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
    void testBatchCreate() {
        List<Person> people = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Person p = new Person();
            p.setName("Person " + i);
            people.add(p);
        }

        List<Person> created = personRepository.createAllBatch(people);

        assertNotNull(created);
        assertEquals(10, created.size());
        for (Person p : created) {
            assertNotNull(p.getId());
        }

        long count = personRepository.count();
        assertEquals(10, count);
    }

    @Test
    void testBatchDelete() {
        // Create test data
        List<Person> people = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Person p = new Person();
            p.setName("Person " + i);
            people.add(p);
        }
        List<Person> created = personRepository.createAllBatch(people);

        // Delete by IDs
        List<Object> ids = created.stream().map(p -> (Object) p.getId()).toList();
        personRepository.deleteAllByIds(ids);

        long count = personRepository.count();
        assertEquals(0, count);
    }

    @Test
    void testBatchMerge() {
        // Create initial data
        Person p1 = new Person();
        p1.setName("Original");
        p1 = personRepository.create(p1);

        // Prepare batch with update to existing and new entity
        List<Person> batch = new ArrayList<>();
        Person update = new Person();
        update.setId(p1.getId());
        update.setName("Updated");
        batch.add(update);

        Person newPerson = new Person();
        newPerson.setName("New Person");
        batch.add(newPerson);

        List<Person> result = personRepository.mergeAllBatch(batch);

        assertEquals(2, result.size());
        assertEquals(2, personRepository.count());

        Person updated = personRepository.findById(p1.getId());
        assertEquals("Updated", updated.getName());
    }

    @Test
    void testDeleteAllByIdsWithEmptyList() {
        personRepository.deleteAllByIds(List.of());
        // Should not throw exception
        assertEquals(0, personRepository.count());
    }

    @Test
    void testCreateAllBatchWithEmptyList() {
        List<Person> result = personRepository.createAllBatch(List.of());
        assertTrue(result.isEmpty());
    }
}
