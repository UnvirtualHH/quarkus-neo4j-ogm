package io.quarkiverse.quarkus.neo4j.ogm.it;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import io.quarkiverse.quarkus.neo4j.ogm.it.model.Person;

/**
 * @QuarkusTest
 *              public class PersonTest {
 *
 * @Inject
 *         PersonBaseRepository repo;
 *
 * @Test
 *       void testGeneratedRepositoryAndMapper() {
 *       Person person = new Person();
 *       person.setId(UUID.randomUUID());
 *       person.setName("Alice");
 *
 *       repo.create(person);
 *
 *       Person loaded = repo.findById(person.getId());
 *       assertNotNull(loaded);
 *       assertEquals("Alice", loaded.getName());
 *       }
 *       }
 **/
