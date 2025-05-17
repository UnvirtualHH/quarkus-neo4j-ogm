package io.quarkiverse.quarkus.neo4j.ogm.it.processor;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.quarkus.neo4j.ogm.it.model.Person;
import io.quarkiverse.quarkus.neo4j.ogm.it.model.PersonMapper;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityWithRelations;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class MapperTest {

    @Inject
    PersonMapper personMapper;

    @Test
    public void testMapperExists() {
        Assertions.assertNotNull(personMapper);
    }

    @Test
    public void testMapperToDb() {
        Person person = new Person();
        person.setName("John Doe");

        EntityWithRelations dbMap = personMapper.toDb(person);
        Assertions.assertNotNull(dbMap);
        Assertions.assertEquals(2, dbMap.getProperties().size());

        Assertions.assertEquals("John Doe", dbMap.getProperties().get("name"));
    }
}
