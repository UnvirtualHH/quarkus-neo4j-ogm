package de.prgrm.quarkus.neo4j.ogm.it.tx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import de.prgrm.quarkus.neo4j.ogm.it.model.Person;
import de.prgrm.quarkus.neo4j.ogm.it.model.PersonBaseRepository;
import de.prgrm.quarkus.neo4j.ogm.it.service.PersonService;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PersonTransactionalTest {

    @Inject
    PersonBaseRepository personRepository;

    @Inject
    PersonService txService;

    @Test
    @Order(1)
    void testTransactionalCommit() {
        Person a = new Person();
        a.setName("TxAlice");

        Person b = new Person();
        b.setName("TxBob");

        txService.createTwoPeople(a, b);

        List<Person> result = personRepository.query(
                "MATCH (n:Person) WHERE n.name IN ['TxAlice', 'TxBob'] RETURN n AS node",
                Map.of());

        assertEquals(2, result.size(), "Both persons should be committed in one transaction");

        var persistedId = result.getFirst().getId().toString();
    }

    @Test
    @Order(2)
    void testTransactionalRollback() {
        long beforeCount = personRepository.count();

        Person c = new Person();
        c.setName("TxCharlie");

        Person d = new Person();
        d.setName("TxDavid");

        assertThrows(RuntimeException.class, () -> txService.createWithError(c, d));

        long afterCount = personRepository.count();
        assertEquals(beforeCount, afterCount, "No new person should be persisted after rollback");
    }

    @Test
    @Order(3)
    void testNonTransactionalBehavior() {
        Person e = new Person();
        e.setName("Eve");

        Person saved = personRepository.create(e);
        assertNotNull(saved.getId());

        assertThrows(Exception.class, () -> {
            personRepository.execute("INVALID CYPHER", Map.of());
        });

        assertTrue(personRepository.existsById(saved.getId().toString()));
    }
}
