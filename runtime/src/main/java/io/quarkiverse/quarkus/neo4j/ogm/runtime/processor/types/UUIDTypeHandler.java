package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.GeneratedValue;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class UUIDTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field) {
        return field.asType().toString().equals("java.util.UUID");
    }

    @Override
    public boolean supportsType(String fqcn) {
        return "java.util.UUID".equals(fqcn);
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        return CodeBlock.of(
                "$L.$L($T.fromString($L.get($S).asString()));\n",
                targetVar,
                resolveSetterName(field),
                ClassName.get("java.util", "UUID"),
                valueSource,
                getPropertyName(field));
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        String getter = resolveGetterName(field);
        String setter = resolveSetterName(field);
        String prop = getPropertyName(field);

        boolean isGenerated = field.getAnnotation(GeneratedValue.class) != null;

        CodeBlock.Builder code = CodeBlock.builder();

        code.addStatement("$T id = $L.$L()",
                ClassName.get("java.util", "UUID"),
                entityVar,
                getter);

        if (isGenerated) {
            code.beginControlFlow("if (id == null)")
                    .addStatement("id = $T.randomUUID()", ClassName.get("java.util", "UUID"))
                    .addStatement("$L.$L(id)", entityVar, setter)
                    .endControlFlow();
        }

        code.beginControlFlow("if (id != null)")
                .addStatement("$L.put($S, id.toString())", mapVar, prop)
                .endControlFlow();

        return code.build();
    }

    @Override
    public CodeBlock generateParameterConversion(String sourceVar) {
        return CodeBlock.of("$L.toString()", sourceVar);
    }
}
