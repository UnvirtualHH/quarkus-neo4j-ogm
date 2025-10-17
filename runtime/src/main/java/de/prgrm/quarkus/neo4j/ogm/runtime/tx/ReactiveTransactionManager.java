package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import org.neo4j.driver.Driver;
import org.neo4j.driver.reactive.ReactiveSession;
import org.neo4j.driver.reactive.ReactiveTransaction;

import io.smallrye.mutiny.Uni;

@ApplicationScoped
public class ReactiveTransactionManager {

    private final Driver driver;

    public ReactiveTransactionManager(Driver driver) {
        this.driver = driver;
    }

    public Uni<ReactiveTxContext> begin() {
        ReactiveSession session = driver.session(ReactiveSession.class);
        return Uni.createFrom().publisher(session.beginTransaction())
                .map(tx -> new ReactiveTxContext(UUID.randomUUID(), session, tx, true))
                .onFailure().invoke(err -> closeQuietly(session));
    }

    public Uni<Void> commit(ReactiveTxContext ctx) {
        if (ctx == null || !ctx.isOwner()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().publisher(ctx.tx.commit())
                .call(() -> Uni.createFrom().publisher(ctx.tx.close()))
                .call(() -> Uni.createFrom().publisher(ctx.session.close()))
                .replaceWithVoid();
    }

    public Uni<Void> rollback(ReactiveTxContext ctx) {
        if (ctx == null || !ctx.isOwner()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().publisher(ctx.tx.rollback())
                .call(() -> Uni.createFrom().publisher(ctx.tx.close()))
                .call(() -> Uni.createFrom().publisher(ctx.session.close()))
                .replaceWithVoid();
    }

    private void closeQuietly(ReactiveSession session) {
        try {
            session.close();
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------
    // Context object
    // ----------------------------------------------------
    public static class ReactiveTxContext {
        private final UUID id;
        final ReactiveSession session;
        final ReactiveTransaction tx;
        private final boolean owner;

        public ReactiveTxContext(UUID id, ReactiveSession session, ReactiveTransaction tx, boolean owner) {
            this.id = id;
            this.session = session;
            this.tx = tx;
            this.owner = owner;
        }

        public UUID getId() {
            return id;
        }

        public boolean isOwner() {
            return owner;
        }

        public ReactiveTransaction getTx() {
            return tx;
        }

        public ReactiveSession getSession() {
            return session;
        }
    }
}
