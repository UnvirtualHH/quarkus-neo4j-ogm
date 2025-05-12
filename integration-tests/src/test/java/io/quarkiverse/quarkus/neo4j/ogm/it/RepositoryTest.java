package io.quarkiverse.quarkus.neo4j.ogm.it;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

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
        assertThat(personBaseRepository).isNotNull();
    }

    @Test
    public void testGeneratedReactiveRepositoryIsPresent() {
        assertThat(personBaseReactiveRepository).isNotNull();
    }
}
