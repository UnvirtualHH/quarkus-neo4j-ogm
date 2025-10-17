package de.prgrm.quarkus.neo4j.ogm.it.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import de.prgrm.quarkus.neo4j.ogm.it.model.Person;
import de.prgrm.quarkus.neo4j.ogm.it.model.PersonBaseRepository;
import de.prgrm.quarkus.neo4j.ogm.runtime.tx.Transactional;

/**
 * Service layer to test @Transactional integration with the Repository and TransactionManager.
 */
@ApplicationScoped
public class PersonService {

    @Inject
    PersonBaseRepository personRepository;

    @Transactional
    public void createTwoPeople(Person a, Person b) {
        personRepository.create(a);
        personRepository.create(b);
    }

    @Transactional
    public void createWithError(Person c, Person d) {
        personRepository.create(c);
        personRepository.create(d);
        throw new RuntimeException("Simulated failure to trigger rollback");
    }
}
