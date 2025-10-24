package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.*;

import org.jboss.logging.Logger;
import org.neo4j.driver.exceptions.Neo4jException;

@Interceptor
@de.prgrm.quarkus.neo4j.ogm.runtime.tx.Transactional
@jakarta.transaction.Transactional
@Priority(Interceptor.Priority.APPLICATION + 10)
public class TransactionalInterceptor {

    private static final Logger LOG = Logger.getLogger(TransactionalInterceptor.class);

    @Inject
    TransactionManager txManager;

    @AroundInvoke
    public Object manageTransaction(InvocationContext ctx) throws Exception {
        boolean newTx = !txManager.isTransactionActive();
        if (newTx) {
            txManager.getOrCreateTransaction();
        }

        try {
            Object result = ctx.proceed();
            if (newTx) {
                txManager.commitAndClose();
            }
            return result;
        } catch (Neo4jException e) {
            LOG.errorf("Neo4j exception in transactional method %s: %s",
                    ctx.getMethod().getName(), e.getMessage());
            if (newTx)
                txManager.rollbackAndClose();
            throw e;
        } catch (Exception e) {
            if (newTx)
                txManager.rollbackAndClose();
            throw e;
        }
    }
}
