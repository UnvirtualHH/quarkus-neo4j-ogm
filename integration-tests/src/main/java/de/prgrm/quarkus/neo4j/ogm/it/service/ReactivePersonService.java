package de.prgrm.quarkus.neo4j.ogm.it.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import de.prgrm.quarkus.neo4j.ogm.it.model.Person;
import de.prgrm.quarkus.neo4j.ogm.it.model.PersonBaseReactiveRepository;
import de.prgrm.quarkus.neo4j.ogm.runtime.tx.ReactiveTransactionManager;
import de.prgrm.quarkus.neo4j.ogm.runtime.tx.ReactiveTransactional;
import io.smallrye.mutiny.Uni;

/**
 * Reactive service layer to test @ReactiveTransactional and ReactiveTransactionManager integration.
 */
@ApplicationScoped
public class ReactivePersonService {

    @Inject
    PersonBaseReactiveRepository personRepository;

    @Inject
    ReactiveTransactionManager txManager;

    @ReactiveTransactional
    public Uni<Void> createTwoPeople(Person a, Person b) {
        return txManager.begin()
                .flatMap(ctx -> personRepository.create(ctx, a)
                        .flatMap(x -> personRepository.create(ctx, b))
                        .call(() -> txManager.commit(ctx))
                        .onFailure().call(err -> txManager.rollback(ctx))
                        .replaceWithVoid());
    }

    @ReactiveTransactional
    public Uni<Void> createWithError(Person c, Person d) {
        return txManager.begin()
                .flatMap(ctx -> personRepository.create(ctx, c)
                        .flatMap(x -> personRepository.create(ctx, d))
                        .flatMap(x -> Uni.createFrom().failure(new RuntimeException("Simulated failure")))
                        .call(() -> txManager.commit(ctx))
                        .onFailure().call(err -> txManager.rollback(ctx))
                        .replaceWithVoid());
    }
}
