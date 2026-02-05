package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

import static de.prgrm.quarkus.neo4j.ogm.runtime.processor.util.MapperUtil.*;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Convert;
import de.prgrm.quarkus.neo4j.ogm.runtime.processor.TypeHandler;

public class ConverterTypeHandler implements TypeHandler {

    private Types typeUtils;
    private Elements elementUtils;

    @Override
    public boolean supports(VariableElement field, Types types, Elements elements) {
        this.typeUtils = types;
        this.elementUtils = elements;
        return field.getAnnotation(Convert.class) != null;
    }

    @Override
    public CodeBlock generateSetterCode(VariableElement field, String targetVar, String valueSource) {
        TypeMirror converterType = getConverterType(field);
        TypeName converterClass = TypeName.get(converterType);
        String converterVar = field.getSimpleName().toString() + "Converter";
        boolean isContextAware = isContextAwareConverter(converterType);

        if (isContextAware) {
            // Context-aware converter: store raw value, convert later after relationships load
            CodeBlock.Builder builder = CodeBlock.builder();
            builder.addStatement("$L.$L($L.get($S).asString())",
                    targetVar,
                    resolveSetterName(field),
                    valueSource,
                    getPropertyName(field));
            return builder.build();
        } else {
            // Standard converter: convert immediately
            CodeBlock.Builder builder = CodeBlock.builder()
                    .addStatement("$T $L = new $T()", converterClass, converterVar, converterClass);
            builder.addStatement("$L.$L($L.toEntityAttribute($L.get($S).asString()))",
                    targetVar,
                    resolveSetterName(field),
                    converterVar,
                    valueSource,
                    getPropertyName(field));
            return builder.build();
        }
    }

    @Override
    public CodeBlock generateToDbCode(VariableElement field, String entityVar, String mapVar) {
        TypeMirror converterType = getConverterType(field);
        TypeName converterClass = TypeName.get(converterType);
        String converterVar = field.getSimpleName().toString() + "Converter";
        String getter = resolveGetterName(field);
        String property = getPropertyName(field);
        boolean isContextAware = isContextAwareConverter(converterType);

        CodeBlock.Builder builder = CodeBlock.builder()
                .addStatement("$T $L = new $T()", converterClass, converterVar, converterClass);

        if (isContextAware) {
            // Context-aware converter: pass entity as second parameter
            builder.addStatement("if ($L.$L() != null) $L.put($S, $L.toGraphProperty($L.$L(), $L))",
                    entityVar, getter,
                    mapVar, property,
                    converterVar, entityVar, getter, entityVar);
        } else {
            // Standard converter: only pass value
            builder.addStatement("if ($L.$L() != null) $L.put($S, $L.toGraphProperty($L.$L()))",
                    entityVar, getter,
                    mapVar, property,
                    converterVar, entityVar, getter);
        }

        return builder.build();
    }

    @Override
    public CodeBlock generatePostLoadConverterCode(VariableElement field, String entityVar) {
        TypeMirror converterType = getConverterType(field);
        boolean isContextAware = isContextAwareConverter(converterType);

        if (!isContextAware) {
            return null; // Only context-aware converters need post-load conversion
        }

        TypeName converterClass = TypeName.get(converterType);
        String converterVar = field.getSimpleName().toString() + "Converter";
        String getter = resolveGetterName(field);
        String setter = resolveSetterName(field);

        CodeBlock.Builder builder = CodeBlock.builder();
        builder.addStatement("$T $L = new $T()", converterClass, converterVar, converterClass);
        builder.addStatement("if ($L.$L() != null) $L.$L($L.toEntityAttribute($L.$L(), $L))",
                entityVar, getter,
                entityVar, setter,
                converterVar, entityVar, getter, entityVar);

        return builder.build();
    }

    private TypeMirror getConverterType(VariableElement field) {
        try {
            field.getAnnotation(Convert.class).value(); // Will throw
            throw new IllegalStateException("Expected MirroredTypeException");
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
    }

    /**
     * Checks if the converter implements ContextAwareAttributeConverter
     */
    private boolean isContextAwareConverter(TypeMirror converterType) {
        if (typeUtils == null || elementUtils == null) {
            return false;
        }

        // Get the ContextAwareAttributeConverter interface type
        TypeElement contextAwareInterface = elementUtils.getTypeElement(
                "de.prgrm.quarkus.neo4j.ogm.runtime.converter.ContextAwareAttributeConverter");

        if (contextAwareInterface == null) {
            return false;
        }

        // Check if converterType implements ContextAwareAttributeConverter
        TypeElement converterElement = (TypeElement) typeUtils.asElement(converterType);
        if (converterElement == null) {
            return false;
        }

        // Check direct implementation
        for (TypeMirror interfaceType : converterElement.getInterfaces()) {
            if (typeUtils.erasure(interfaceType).toString().equals(
                    "de.prgrm.quarkus.neo4j.ogm.runtime.converter.ContextAwareAttributeConverter")) {
                return true;
            }
        }

        // Check superclass (in case it's inherited)
        TypeMirror superclass = converterElement.getSuperclass();
        if (superclass != null && superclass instanceof DeclaredType) {
            return isContextAwareConverter(superclass);
        }

        return false;
    }
}
