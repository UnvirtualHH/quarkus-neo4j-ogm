package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;
import org.neo4j.driver.*;

@ApplicationScoped
public class TransactionManager {

    private static final Logger LOG = Logger.getLogger(TransactionManager.class);
    private static final ThreadLocal<Transaction> CURRENT_TX = new ThreadLocal<>();
    private static final ThreadLocal<Session> CURRENT_SESSION = new ThreadLocal<>();

    private final Driver driver;

    public TransactionManager(Driver driver) {
        this.driver = driver;
    }

    /**
     * Returns the current transaction, or creates a new one in WRITE mode.
     */
    public Transaction getOrCreateTransaction() {
        return getOrCreateTransaction(AccessMode.WRITE);
    }

    /**
     * Returns the current transaction, or creates a new one with the given access mode.
     */
    public Transaction getOrCreateTransaction(AccessMode mode) {
        Transaction tx = CURRENT_TX.get();
        if (tx == null) {
            Session session = driver.session(SessionConfig.builder()
                    .withDefaultAccessMode(mode)
                    .build());
            tx = session.beginTransaction();
            CURRENT_SESSION.set(session);
            CURRENT_TX.set(tx);
            log("BEGIN (" + mode + ")");
        }
        return tx;
    }

    public boolean isTransactionActive() {
        Transaction tx = CURRENT_TX.get();
        return tx != null && tx.isOpen();
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
            log("COMMIT FAILED → rolling back: " + e.getMessage());
            safeRollback(tx);
            throw e;
        } finally {
            cleanup(session);
        }
    }

    public void rollbackAndClose() {
        Transaction tx = CURRENT_TX.get();
        Session session = CURRENT_SESSION.get();

        try {
            safeRollback(tx);
        } finally {
            cleanup(session);
        }
    }

    private void safeRollback(Transaction tx) {
        if (tx != null && tx.isOpen()) {
            try {
                tx.rollback();
                log("ROLLBACK");
            } catch (Exception e) {
                log("ROLLBACK FAILED: " + e.getMessage());
            }
        }
    }

    private void cleanup(Session session) {
        Transaction tx = CURRENT_TX.get();
        CURRENT_TX.remove();
        CURRENT_SESSION.remove();

        try {
            if (tx != null && tx.isOpen()) {
                log("WARNING: TX still open during cleanup");
                tx.close();
            }
        } catch (Exception e) {
            log("TX CLOSE FAILED: " + e.getMessage());
        }

        try {
            if (session != null && session.isOpen()) {
                session.close();
                log("SESSION CLOSED");
            }
        } catch (Exception e) {
            log("SESSION CLOSE FAILED: " + e.getMessage());
        }
    }

    private void log(String msg) {
        LOG.debugf("[TX] %s (Thread: %s)", msg, Thread.currentThread().getName());
    }
}
