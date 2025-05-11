package io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.types;

import static io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.VariableElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.enums.Convert;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class ConverterTypeHandler implements TypeHandler {

    @Override
    public boolean supports(VariableElement field) {
        return field.getAnnotation(Convert.class) != null;
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        TypeMirror converterType = getConverterType(field);
        TypeName converterClass = TypeName.get(converterType);
        String converterVar = field.getSimpleName().toString() + "Converter";

        return CodeBlock.builder()
                .addStatement("$T $L = new $T()", converterClass, converterVar, converterClass)
                .addStatement("$L.$L($L.toEntityAttribute($L.get($S).asString()))",
                        targetVar, resolveSetterName(field), converterVar, valueSource, getPropertyName(field))
                .build();
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar) {
        TypeMirror converterType = getConverterType(field);
        TypeName converterClass = TypeName.get(converterType);
        String converterVar = field.getSimpleName().toString() + "Converter";
        String getter = resolveGetterName(field);
        String property = getPropertyName(field);

        return CodeBlock.builder()
                .addStatement("$T $L = new $T()", converterClass, converterVar, converterClass)
                .addStatement("if ($L.$L() != null) params.put($S, $L.toGraphProperty($L.$L()))",
                        entityVar, getter, property, converterVar, entityVar, getter)
                .build();
    }

    private TypeMirror getConverterType(VariableElement field) {
        try {
            field.getAnnotation(Convert.class).value(); // Will throw
            throw new IllegalStateException("Expected MirroredTypeException");
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
    }
}
