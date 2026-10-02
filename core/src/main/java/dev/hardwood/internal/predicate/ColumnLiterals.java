/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.predicate;

import dev.hardwood.internal.conversion.LogicalTypeConverter;
import dev.hardwood.internal.reader.TimestampAccessorKind;
import dev.hardwood.internal.schema.TextColumns;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.schema.ColumnSchema;

/// The literal types a filter predicate takes on a column, as a refusal names them: the value of
/// the column's logical accessor, where it has one, and that of its physical accessor.
///
/// The switch over [LogicalType] is exhaustive rather than a list of exceptions, so an annotation
/// added later has to state which literal it takes.
final class ColumnLiterals {

    private ColumnLiterals() {
    }

    /// The refusal of a literal the column does not take, naming the column, what it is, the
    /// literals it takes and the one given, such as `a LocalDate`.
    static IllegalArgumentException notTaken(String columnName, ColumnSchema columnSchema, String literal) {
        return new IllegalArgumentException("Column '" + columnName + "' is " + describe(columnSchema)
                + ", which takes " + taken(columnSchema) + " literals, not " + literal);
    }

    /// What the column is, as a refusal names it.
    static String describe(ColumnSchema columnSchema) {
        if (LogicalTypeConverter.isLegacyInt96Timestamp(columnSchema.type(), columnSchema.logicalType())) {
            return TimestampAccessorKind.describeLegacyInt96();
        }
        if (columnSchema.logicalType() != null) {
            return "annotated " + columnSchema.logicalType();
        }
        return "an unannotated " + columnSchema.type();
    }

    /// The literal types the column takes, such as `LocalDate and int`.
    static String taken(ColumnSchema columnSchema) {
        String physical = switch (columnSchema.type()) {
            case BOOLEAN -> "boolean";
            case INT32 -> "int";
            case INT64 -> "long";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case INT96, BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY -> "byte[]";
        };
        String logical = logical(columnSchema);
        return logical == null ? physical : logical + " and " + physical;
    }

    /// The value of the column's logical accessor, or `null` where only the physical accessor reads it.
    ///
    /// Text is [TextColumns]'s to answer, being the `String` literal's contract as well as this
    /// name's, so an annotation it calls text reaches the switch below only over a physical type
    /// that cannot carry it, which `FileSchema` has already dropped.
    private static String logical(ColumnSchema columnSchema) {
        PhysicalType type = columnSchema.type();
        LogicalType logicalType = columnSchema.logicalType();
        if (TextColumns.holdsText(type, logicalType)) {
            return "String";
        }
        if (LogicalTypeConverter.isLegacyInt96Timestamp(type, logicalType)) {
            return "Instant";
        }
        if (logicalType == null) {
            return null;
        }
        return switch (logicalType) {
            case LogicalType.StringType ignored -> throw textElsewhere(columnSchema);
            case LogicalType.EnumType ignored -> throw textElsewhere(columnSchema);
            case LogicalType.JsonType ignored -> throw textElsewhere(columnSchema);
            case LogicalType.DecimalType ignored -> "BigDecimal";
            case LogicalType.Float16Type ignored -> "float";
            case LogicalType.UuidType ignored -> "UUID";
            case LogicalType.IntervalType ignored -> "PqInterval";
            case LogicalType.DateType ignored -> "LocalDate";
            case LogicalType.TimeType ignored -> "LocalTime";
            case LogicalType.TimestampType timestamp -> timestamp.isAdjustedToUTC() ? "Instant" : "LocalDateTime";
            case LogicalType.IntType ignored -> null;
            case LogicalType.BsonType ignored -> null;
            case LogicalType.GeometryType ignored -> null;
            case LogicalType.GeographyType ignored -> null;
            case LogicalType.NullType ignored -> null;
            case LogicalType.VariantType ignored -> null;
            case LogicalType.ListType ignored -> null;
            case LogicalType.MapType ignored -> null;
        };
    }

    private static IllegalStateException textElsewhere(ColumnSchema columnSchema) {
        return new IllegalStateException("Column '" + columnSchema.name() + "' is " + columnSchema.type()
                + " annotated " + columnSchema.logicalType() + ", a pairing the schema drops");
    }
}
