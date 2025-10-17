package de.prgrm.quarkus.neo4j.ogm.it.tx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.*;

import de.prgrm.quarkus.neo4j.ogm.it.model.Person;
import de.prgrm.quarkus.neo4j.ogm.it.model.PersonBaseReactiveRepository;
import de.prgrm.quarkus.neo4j.ogm.it.service.ReactivePersonService;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReactivePersonTransactionalTest {

    @Inject
    PersonBaseReactiveRepository personRepository;

    @Inject
    ReactivePersonService txService;

    @Test
    @Order(1)
    void testReactiveTransactionalCommit() {
        Person a = new Person();
        a.setName("RxAlice");

        Person b = new Person();
        b.setName("RxBob");

        // Execute and block until done
        txService.createTwoPeople(a, b).await().indefinitely();

        List<Person> result = personRepository.query(
                "MATCH (n:Person) WHERE n.name IN ['RxAlice', 'RxBob'] RETURN n AS node",
                Map.of())
                .collect().asList()
                .await().indefinitely();

        assertEquals(2, result.size(), "Both persons should be committed in one transaction");
    }

    @Test
    @Order(2)
    void testReactiveTransactionalRollback() {
        long beforeCount = personRepository.count().await().indefinitely();

        Person c = new Person();
        c.setName("RxCharlie");

        Person d = new Person();
        d.setName("RxDavid");

        assertThrows(RuntimeException.class,
                () -> txService.createWithError(c, d).await().indefinitely());

        long afterCount = personRepository.count().await().indefinitely();
        assertEquals(beforeCount, afterCount,
                "No new person should be persisted after rollback");
    }

    @Test
    @Order(3)
    void testReactiveNonTransactionalBehavior() {
        Person e = new Person();
        e.setName("RxEve");

        Person saved = personRepository.create(e).await().indefinitely();
        assertNotNull(saved.getId());

        assertThrows(Exception.class, () -> personRepository.execute("INVALID CYPHER", Map.of())
                .await().indefinitely());

        Boolean exists = personRepository.existsById(saved.getId().toString()).await().indefinitely();
        assertTrue(exists);
    }
}
