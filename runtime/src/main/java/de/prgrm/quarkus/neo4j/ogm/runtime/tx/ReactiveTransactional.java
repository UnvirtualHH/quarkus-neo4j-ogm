package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.interceptor.InterceptorBinding;

/**
 * Marks a method whose returned {@link io.smallrye.mutiny.Uni} should be wrapped in a reactive
 * transaction boundary by {@link ReactiveTransactionalInterceptor}.
 *
 * <p>
 * <strong>Limitation:</strong> this annotation does <em>not</em> automatically propagate the
 * transaction into repository calls inside the method. Mutiny has no thread-bound context comparable
 * to the imperative {@link Transactional}, so the begun {@link ReactiveTransactionManager.ReactiveTxContext}
 * cannot be injected into the already-constructed pipeline. Repository operations that do not receive
 * the {@code ReactiveTxContext} explicitly will open their own auto-committing sessions and run
 * <em>outside</em> this boundary.
 *
 * <p>
 * To get real transactional scoping reactively, thread the context through explicitly via the
 * {@code ReactiveTxContext} overloads:
 *
 * <pre>{@code
 * return txManager.begin()
 *         .flatMap(ctx -> repo.create(ctx, a)
 *                 .flatMap(x -> repo.create(ctx, b))
 *                 .call(() -> txManager.commit(ctx))
 *                 .onFailure().call(err -> txManager.rollback(ctx))
 *                 .replaceWithVoid());
 * }</pre>
 */
@Inherited
@InterceptorBinding
@Target({ METHOD, TYPE })
@Retention(RUNTIME)
public @interface ReactiveTransactional {
}
