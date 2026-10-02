/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.schema;

import dev.hardwood.internal.conversion.FixedWidths;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;

/// Checks that a logical type annotation is legal for the physical type it annotates.
///
/// A logical type is only meaningful over the physical representations parquet-format defines
/// for it — a `DATE` is days in an `INT32` and nothing else, a `UUID` is exactly 16 fixed bytes.
/// An illegal pairing produces a file whose annotation no reader can honour, so the writer
/// rejects it where the schema is declared rather than emitting it.
///
/// Only the writer refuses. A file that already exists on disk has to be readable whatever a
/// foreign writer put in its footer, so the reader drops an annotation that fails the same
/// pairings and reads the column as its physical type: see
/// [dev.hardwood.internal.conversion.LogicalTypeConverter#conversionFault].
public class LogicalTypeValidator {

    /// Validates a primitive column's annotation.
    ///
    /// @param columnName the column name, for the failure message
    /// @param type the column's physical type
    /// @param repetition the column's repetition
    /// @param typeLength the `FIXED_LEN_BYTE_ARRAY` byte length, `null` for any other type
    /// @param logicalType the annotation to validate, `null` for an unannotated column
    /// @throws IllegalArgumentException if the annotation is not legal for the physical type, its
    ///         type length, or — for `UNKNOWN` — the column's repetition
    public static void validate(String columnName, PhysicalType type, RepetitionType repetition,
                                Integer typeLength, LogicalType logicalType) {
        if (logicalType == null) {
            return;
        }
        if (logicalType instanceof LogicalType.NullType) {
            requireNullable(columnName, repetition);
            return;
        }
        Pairing pairing = AnnotationPairings.check(type, typeLength, logicalType);
        if (pairing instanceof Pairing.Illegal illegal) {
            throw refusal(columnName, type, typeLength, logicalType, illegal.fault());
        }
    }

    /// The writer's wording for a pairing [AnnotationPairings#check] refuses. It addresses the author
    /// of a schema being declared, naming the column they wrote, where the reader's wording
    /// describes a file that already exists.
    private static IllegalArgumentException refusal(String columnName, PhysicalType type, Integer typeLength,
                                                   LogicalType logicalType, Pairing.Fault fault) {
        return switch (fault) {
            case Pairing.Fault.WrongPhysicalType wrong -> wrongType(columnName, type, logicalType, wrong);
            case Pairing.Fault.WrongWidth wrong ->
                    widthRefusal(columnName, logicalType, wrong.expected(), typeLength);
            case Pairing.Fault.PrecisionTooLarge tooLarge -> new IllegalArgumentException(
                    "DECIMAL precision " + tooLarge.precision() + " exceeds the maximum "
                            + tooLarge.maxPrecision() + " a " + type + " can represent on column " + columnName);
            case Pairing.Fault.GroupAnnotation ignored -> logicalType instanceof LogicalType.VariantType
                    ? new IllegalArgumentException("VARIANT annotates a group of metadata and value children, "
                            + "which the writer does not yet build: " + columnName)
                    : groupAnnotation(columnName, logicalType);
        };
    }

    private static IllegalArgumentException wrongType(String columnName, PhysicalType type,
                                                      LogicalType logicalType,
                                                      Pairing.Fault.WrongPhysicalType wrong) {
        if (logicalType instanceof LogicalType.DecimalType) {
            return new IllegalArgumentException("DECIMAL is not valid on physical type " + type
                    + " (column " + columnName + "); use INT32, INT64, BYTE_ARRAY or FIXED_LEN_BYTE_ARRAY");
        }
        if (logicalType instanceof LogicalType.TimestampType) {
            return new IllegalArgumentException(logicalType + " annotates an INT64 or a FIXED_LEN_BYTE_ARRAY("
                    + FixedWidths.FLBA12_TIMESTAMP + ") column, not " + type + " (column " + columnName + ")");
        }
        return new IllegalArgumentException(logicalType + " annotates a " + wrong.allowed().getFirst()
                + " column, not " + type + " (column " + columnName + ")");
    }

    private static IllegalArgumentException widthRefusal(String columnName, LogicalType logicalType,
                                                        int expected, Integer typeLength) {
        return new IllegalArgumentException(logicalType + " annotates a FIXED_LEN_BYTE_ARRAY of length "
                + expected + ", not " + typeLength + " (column " + columnName + ")");
    }

    /// `UNKNOWN` annotates a column of any physical type whose every value is null, which a
    /// `REQUIRED` column can never be: no value it could legally hold matches the annotation,
    /// and reading one back fails on the null the annotation promises.
    private static void requireNullable(String columnName, RepetitionType repetition) {
        if (repetition == RepetitionType.REQUIRED) {
            throw new IllegalArgumentException("UNKNOWN annotates a column holding only nulls, so it cannot be "
                    + "REQUIRED (column " + columnName + ")");
        }
    }

    private static IllegalArgumentException groupAnnotation(String columnName, LogicalType logicalType) {
        return new IllegalArgumentException(logicalType + " annotates a group, not a primitive column: "
                + columnName + "; declare it with the list or map builder verb instead");
    }

    private LogicalTypeValidator() {
    }
}
