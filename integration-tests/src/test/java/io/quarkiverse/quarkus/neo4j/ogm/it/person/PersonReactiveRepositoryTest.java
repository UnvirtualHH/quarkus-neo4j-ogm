package io.quarkiverse.quarkus.neo4j.ogm.it.person;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import io.quarkiverse.quarkus.neo4j.ogm.it.model.Person;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.PersonBaseReactiveRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PersonReactiveRepositoryTest {

    @Inject
    PersonBaseReactiveRepository personRepository;

    private static String createdId;

    @Test
    @Order(1)
    void testCreate() {
        Person person = new Person();
        person.setName("Tester");

        Person created = personRepository.create(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(created.getId());
        assertEquals("Tester", created.getName());

        createdId = created.getId().toString();
    }

    @Test
    @Order(2)
    void testFindById() {
        Person found = personRepository.findById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertNotNull(found);
        assertEquals("Tester", found.getName());
    }

    @Test
    @Order(3)
    void testUpdate() {
        Person person = personRepository.findById(createdId)
                .await().indefinitely();
        person.setName("Updated");

        Person updated = personRepository.update(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertEquals("Updated", updated.getName());
    }

    @Test
    @Order(4)
    void testExists() {
        Boolean existsById = personRepository.existsById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(existsById);

        Person person = personRepository.findById(createdId).await().indefinitely();

        Boolean exists = personRepository.exists(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(exists);
    }

    @Test
    @Order(5)
    void testQuery() {
        AssertSubscriber<Person> subscriber = personRepository
                .query("MATCH (n:Person) WHERE n.name = $name RETURN n AS node", Map.of("name", "Updated"))
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        List<Person> list = subscriber
                .awaitItems(1)
                .assertCompleted()
                .getItems();

        assertEquals(1, list.size());
        assertEquals("Updated", list.getFirst().getName());
    }

    @Test
    @Order(6)
    void testQueryScalar() {
        Long count = personRepository.queryScalar(
                "MATCH (n:Person) RETURN count(n) AS cnt",
                Map.of(),
                record -> record.get("cnt").asLong())
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertTrue(count > 0);
    }

    @Test
    @Order(7)
    void testFindAllAndCount() {
        AssertSubscriber<Person> multiSub = personRepository.findAll()
                .subscribe().withSubscriber(AssertSubscriber.create(10));

        multiSub
                .awaitCompletion()
                .assertCompleted();

        List<Person> all = multiSub.getItems();
        assertFalse(all.isEmpty());

        long count = personRepository.count()
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem()
                .assertCompleted()
                .getItem();

        assertEquals(all.size(), count);
    }

    @Test
    @Order(8)
    void testMerge() {
        Person person = new Person();
        person.setId(java.util.UUID.fromString(createdId));
        person.setName("Merged");

        Person merged = personRepository.merge(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertEquals("Merged", merged.getName());
    }

    @Test
    @Order(9)
    void testDelete() {
        Person person = personRepository.findById(createdId).await().indefinitely();

        personRepository.delete(person)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted();

        Boolean exists = personRepository.existsById(createdId)
                .subscribe().withSubscriber(UniAssertSubscriber.create())
                .awaitItem().assertCompleted()
                .getItem();

        assertFalse(exists);
    }
}
