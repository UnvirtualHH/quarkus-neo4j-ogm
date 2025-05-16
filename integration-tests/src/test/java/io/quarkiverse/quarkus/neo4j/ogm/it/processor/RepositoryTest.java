package io.quarkiverse.quarkus.neo4j.ogm.it.processor;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.quarkus.neo4j.ogm.it.model.PersonBaseReactiveRepository;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.PersonBaseRepository;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class RepositoryTest {

    @Inject
    PersonBaseRepository personBaseRepository;

    @Inject
    PersonBaseReactiveRepository personBaseReactiveRepository;

    @Test
    public void testGeneratedRepositoryIsPresent() {
        Assertions.assertNotNull(personBaseRepository);
    }

    @Test
    public void testGeneratedReactiveRepositoryIsPresent() {
        Assertions.assertNotNull(personBaseReactiveRepository);
    }
}
