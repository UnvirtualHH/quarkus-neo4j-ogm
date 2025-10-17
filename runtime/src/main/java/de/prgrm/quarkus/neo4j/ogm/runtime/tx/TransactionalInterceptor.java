package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.*;

@Interceptor
@Transactional
@Priority(Interceptor.Priority.APPLICATION + 10)
public class TransactionalInterceptor {

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
        } catch (Exception e) {
            if (newTx) {
                txManager.rollbackAndClose();
            }
            throw e;
        }
    }
}