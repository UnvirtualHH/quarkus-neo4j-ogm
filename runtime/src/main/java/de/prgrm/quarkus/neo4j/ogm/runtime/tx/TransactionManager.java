package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import jakarta.enterprise.context.ApplicationScoped;

import org.neo4j.driver.*;

/**
 * Manages Neo4j transactions in a ThreadLocal context.
 * Supports declarative @Transactional semantics.
 */
@ApplicationScoped
public class TransactionManager {

    private static final ThreadLocal<Transaction> CURRENT_TX = new ThreadLocal<>();
    private static final ThreadLocal<Session> CURRENT_SESSION = new ThreadLocal<>();

    private final Driver driver;

    public TransactionManager(Driver driver) {
        this.driver = driver;
    }

    public Transaction getOrCreateTransaction() {
        Transaction tx = CURRENT_TX.get();
        if (tx == null) {
            Session session = driver.session(SessionConfig.builder()
                    .withDefaultAccessMode(AccessMode.WRITE)
                    .build());
            tx = session.beginTransaction();
            CURRENT_SESSION.set(session);
            CURRENT_TX.set(tx);
            log("BEGIN");
        }
        return tx;
    }

    public boolean isTransactionActive() {
        return CURRENT_TX.get() != null;
    }

    public void commitAndClose() {
        Transaction tx = CURRENT_TX.get();
        Session session = CURRENT_SESSION.get();

        try {
            if (tx != null && tx.isOpen()) {
                tx.commit();
                log("COMMIT");
            }
        } catch (Exception e) {
            log("COMMIT FAILED → forcing rollback");
            rollbackAndClose();
            throw e;
        } finally {
            cleanup(session);
        }
    }

    public void rollbackAndClose() {
        Transaction tx = CURRENT_TX.get();
        Session session = CURRENT_SESSION.get();

        try {
            if (tx != null && tx.isOpen()) {
                tx.rollback();
                log("ROLLBACK");
            }
        } catch (Exception e) {
            log("ROLLBACK FAILED: " + e.getMessage());
        } finally {
            cleanup(session);
        }
    }

    private void cleanup(Session session) {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } finally {
            CURRENT_TX.remove();
            CURRENT_SESSION.remove();
        }
    }

    private static void log(String msg) {
        System.out.println("[TX] " + msg + " (Thread: " + Thread.currentThread().getName() + ")");
    }
}
