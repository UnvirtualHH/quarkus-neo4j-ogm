package io.quarkiverse.quarkus.neo4j.ogm.it.person;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.neo4j.ogm.it.model.Person;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.PersonBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PersonRepositoryTest {

    @Inject
    PersonBaseRepository personRepository;

    private static String createdId;

    @Test
    @Order(1)
    void testCreate() {
        Person person = new Person();
        person.setName("Tester");

        Person created = personRepository.create(person);
        assertNotNull(created.getId());
        assertEquals("Tester", created.getName());

        createdId = created.getId().toString();
    }

    @Test
    @Order(2)
    void testFindById() {
        Person found = personRepository.findById(createdId);
        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(3)
    void testUpdate() {
        Person person = personRepository.findById(createdId);
        person.setName("Updated");

        Person updated = personRepository.update(person);
        assertEquals("Updated", updated.getName());
    }

    @Test
    @Order(4)
    void testExists() {
        assertTrue(personRepository.existsById(createdId));
        assertTrue(personRepository.exists(personRepository.findById(createdId)));
    }

    @Test
    @Order(5)
    void testQuery() {
        List<Person> people = personRepository.query("MATCH (n:Person) WHERE n.name = $name RETURN n AS node",
                Map.of("name", "Updated"));
        assertEquals(1, people.size());
        assertEquals("Updated", people.get(0).getName());
    }

    @Test
    @Order(6)
    void testQueryScalar() {
        Long count = personRepository.queryScalar(
                "MATCH (n:Person) RETURN count(n) AS cnt",
                record -> record.get("cnt").asLong());

        assertTrue(count > 0);
    }

    @Test
    @Order(7)
    void testFindAllAndCount() {
        List<Person> all = personRepository.findAll();
        assertFalse(all.isEmpty());

        long count = personRepository.count();
        assertEquals(all.size(), count);
    }

    @Test
    @Order(8)
    void testMerge() {
        Person person = new Person();
        person.setId(java.util.UUID.fromString(createdId));
        person.setName("Merged");

        Person merged = personRepository.merge(person);
        assertEquals("Merged", merged.getName());
    }

    @Test
    @Order(9)
    void testDelete() {
        Person person = personRepository.findById(createdId);
        personRepository.delete(person);

        assertFalse(personRepository.existsById(createdId));
    }
}
