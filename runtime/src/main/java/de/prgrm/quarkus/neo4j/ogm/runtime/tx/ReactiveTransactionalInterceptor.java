package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import io.smallrye.mutiny.Uni;

@Interceptor
@ReactiveTransactional
@Priority(Interceptor.Priority.APPLICATION + 10)
public class ReactiveTransactionalInterceptor {

    @Inject
    ReactiveTransactionManager txManager;

    @AroundInvoke
    public Object around(InvocationContext ctx) throws Exception {
        Object result = ctx.proceed();
        if (!(result instanceof Uni<?> uni)) {
            return result;
        }

        return txManager.begin()
                .flatMap(txCtx -> uni
                        .onItem().transformToUni(item -> txManager.commit(txCtx).replaceWith(item))
                        .onFailure().call(err -> txManager.rollback(txCtx)));
    }
}
