package de.prgrm.quarkus.neo4j.ogm.runtime.tx;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.*;

import jakarta.interceptor.InterceptorBinding;

@Inherited
@InterceptorBinding
@Target({ METHOD, TYPE })
@Retention(RUNTIME)
public @interface Transactional {
}
