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
    @SuppressWarnings("unchecked")
    public Object around(InvocationContext ctx) throws Exception {
        Object result = ctx.proceed();

        // Check if result is Uni using class hierarchy instead of instanceof
        Class<?> resultClass = result != null ? result.getClass() : null;
        if (resultClass == null || !Uni.class.isAssignableFrom(resultClass)) {
            return result;
        }

        Uni<?> uni = (Uni<?>) result;
        return txManager.begin()
                .flatMap(txCtx -> uni
                        .onItem().transformToUni(item -> txManager.commit(txCtx).replaceWith(item))
                        .onFailure().call(err -> txManager.rollback(txCtx)));
    }
}
