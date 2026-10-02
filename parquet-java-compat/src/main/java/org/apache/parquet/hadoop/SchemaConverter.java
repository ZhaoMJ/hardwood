/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.apache.parquet.hadoop;

import java.util.ArrayList;
import java.util.List;

import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import dev.hardwood.internal.schema.LogicalTypeAnnotations;
import dev.hardwood.metadata.ConvertedType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;

/// Converts Hardwood schema to parquet-java compatible schema types.
class SchemaConverter {

    /// Convert a Hardwood FileSchema to a parquet-java MessageType.
    ///
    /// @param fileSchema the Hardwood schema
    /// @return the MessageType
    static MessageType toMessageType(FileSchema fileSchema) {
        SchemaNode.GroupNode root = fileSchema.getRootNode();
        List<Type> fields = new ArrayList<>();
        for (SchemaNode child : root.children()) {
            fields.add(toType(child));
        }
        return new MessageType(fileSchema.getName(), fields);
    }

    private static Type toType(SchemaNode node) {
        Type.Repetition repetition = toRepetition(node.repetitionType());

        if (node instanceof SchemaNode.PrimitiveNode primitive) {
            return new PrimitiveType(
                    repetition,
                    toPrimitiveTypeName(primitive.type()),
                    primitive.name(),
                    toOriginalType(LogicalTypeAnnotations.of(primitive.type(), primitive.logicalType())));
        }
        else if (node instanceof SchemaNode.GroupNode group) {
            List<Type> children = new ArrayList<>();
            for (SchemaNode child : group.children()) {
                children.add(toType(child));
            }
            return new GroupType(
                    repetition,
                    group.name(),
                    toOriginalType(LogicalTypeAnnotations.ofGroup(group.convertedType(), group.logicalType())),
                    children);
        }
        throw new IllegalArgumentException("Unknown schema node type: " + node.getClass());
    }

    private static Type.Repetition toRepetition(RepetitionType repetition) {
        return switch (repetition) {
            case REQUIRED -> Type.Repetition.REQUIRED;
            case OPTIONAL -> Type.Repetition.OPTIONAL;
            case REPEATED -> Type.Repetition.REPEATED;
        };
    }

    private static PrimitiveType.PrimitiveTypeName toPrimitiveTypeName(PhysicalType type) {
        return switch (type) {
            case BOOLEAN -> PrimitiveType.PrimitiveTypeName.BOOLEAN;
            case INT32 -> PrimitiveType.PrimitiveTypeName.INT32;
            case INT64 -> PrimitiveType.PrimitiveTypeName.INT64;
            case INT96 -> PrimitiveType.PrimitiveTypeName.INT96;
            case FLOAT -> PrimitiveType.PrimitiveTypeName.FLOAT;
            case DOUBLE -> PrimitiveType.PrimitiveTypeName.DOUBLE;
            case BYTE_ARRAY -> PrimitiveType.PrimitiveTypeName.BINARY;
            case FIXED_LEN_BYTE_ARRAY -> PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY;
        };
    }

    /// The `OriginalType` parquet-java reports for a node: the converted type Hardwood writes for
    /// the node's annotation, `null` where none is written.
    private static OriginalType toOriginalType(LogicalTypeAnnotations annotations) {
        return toOriginalType(annotations.convertedType());
    }

    private static OriginalType toOriginalType(ConvertedType convertedType) {
        if (convertedType == null)
            return null;
        return switch (convertedType) {
            case UTF8 -> OriginalType.UTF8;
            case MAP -> OriginalType.MAP;
            case MAP_KEY_VALUE -> OriginalType.MAP_KEY_VALUE;
            case LIST -> OriginalType.LIST;
            case ENUM -> OriginalType.ENUM;
            case DECIMAL -> OriginalType.DECIMAL;
            case DATE -> OriginalType.DATE;
            case TIME_MILLIS -> OriginalType.TIME_MILLIS;
            case TIME_MICROS -> OriginalType.TIME_MICROS;
            case TIMESTAMP_MILLIS -> OriginalType.TIMESTAMP_MILLIS;
            case TIMESTAMP_MICROS -> OriginalType.TIMESTAMP_MICROS;
            case UINT_8 -> OriginalType.UINT_8;
            case UINT_16 -> OriginalType.UINT_16;
            case UINT_32 -> OriginalType.UINT_32;
            case UINT_64 -> OriginalType.UINT_64;
            case INT_8 -> OriginalType.INT_8;
            case INT_16 -> OriginalType.INT_16;
            case INT_32 -> OriginalType.INT_32;
            case INT_64 -> OriginalType.INT_64;
            case JSON -> OriginalType.JSON;
            case BSON -> OriginalType.BSON;
            case INTERVAL -> OriginalType.INTERVAL;
        };
    }
}
