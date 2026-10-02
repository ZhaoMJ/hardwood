/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.schema;

import java.math.BigDecimal;
import java.util.List;

import dev.hardwood.internal.conversion.FixedWidths;
import dev.hardwood.metadata.ConvertedType;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;

import static dev.hardwood.internal.schema.Pairing.Illegal;
import static dev.hardwood.internal.schema.Pairing.Legal;
import static dev.hardwood.internal.schema.Pairing.Fault.GroupAnnotation;
import static dev.hardwood.internal.schema.Pairing.Fault.PrecisionTooLarge;
import static dev.hardwood.internal.schema.Pairing.Fault.WrongPhysicalType;
import static dev.hardwood.internal.schema.Pairing.Fault.WrongWidth;

/// Which pairings of physical type, annotation and width parquet-format defines, and which
/// annotations name an order over their column's values.
///
/// Each question is answered here and nowhere else. The writer refuses a pairing [#check] calls illegal
/// ([LogicalTypeValidator]) and the reader drops the annotation of one
/// (`LogicalTypeConverter.conversionFault`), which is what parquet-format requires of a reader:
/// "readers should ignore both the logical type annotation and column order for that column. Only
/// the physical type information should be used to process the column's data." A column whose
/// annotation [#namesAnOrder] denies has no bounds recorded for it, none read from it, and no
/// ordered predicate admitted on it.
///
/// Both switches are exhaustive with no `default`, so a new annotation does not compile until it is
/// answered, and a pairing the format newly defines is one cell of the first one's grid.
///
/// Every cell is taken from the specification, and each arm cites the sentence that settles it: `LogicalTypes.md` and `parquet.thrift` at
/// parquet-format `bf099392`. `FILE` (union field 19) is absent because [LogicalType] does not model
/// it (#1413); a footer carrying it decodes to an unrecognized union member and the annotation is
/// dropped, which is what the format asks of a reader that does not recognize one.
public final class AnnotationPairings {

    private static final List<PhysicalType> BYTE_ARRAY_ONLY = List.of(PhysicalType.BYTE_ARRAY);
    private static final List<PhysicalType> FIXED_ONLY = List.of(PhysicalType.FIXED_LEN_BYTE_ARRAY);
    private static final List<PhysicalType> INT32_ONLY = List.of(PhysicalType.INT32);
    private static final List<PhysicalType> INT64_ONLY = List.of(PhysicalType.INT64);
    private static final List<PhysicalType> TIMESTAMP_TYPES =
            List.of(PhysicalType.INT64, PhysicalType.FIXED_LEN_BYTE_ARRAY);
    private static final List<PhysicalType> DECIMAL_TYPES = List.of(PhysicalType.INT32, PhysicalType.INT64,
            PhysicalType.BYTE_ARRAY, PhysicalType.FIXED_LEN_BYTE_ARRAY);

    private static final Pairing LEGAL = new Legal();

    /// `log10(2)` to forty places, which makes [#maxDecimalPrecision(int)] exact for every width an
    /// `i32` can declare: `(8 * length - 1) * log10(2)` never comes within `1e-11` of an
    /// integer there, and this constant's error at the widest width is below `2e-30`.
    private static final BigDecimal LOG10_2 = new BigDecimal("0.3010299956639811952137388947244930267681");

    private AnnotationPairings() {
    }

    /// Whether parquet-format defines an order over the values of a column annotated `annotation`.
    ///
    /// It defines none for `INTERVAL`, `UNKNOWN`, `VARIANT`, `GEOMETRY`, `GEOGRAPHY`, `LIST` and
    /// `MAP`, and states for `INTERVAL` that no `min` / `max` should be written at all. The writer
    /// records no bounds for such a column and the reader reads none from one, since a bound in an
    /// order a reader cannot know would prune away live rows, and the ordered operators are refused
    /// on it. One answer serves all three, the format stating one rule.
    ///
    /// The switch is exhaustive rather than a list of the annotations without an order, so one
    /// added later has to say which side it falls on.
    ///
    /// @param annotation the column's annotation, `null` for an unannotated column, whose physical
    ///        type names its order
    public static boolean namesAnOrder(LogicalType annotation) {
        if (annotation == null) {
            return true;
        }
        return switch (annotation) {
            case LogicalType.StringType ignored -> true;
            case LogicalType.EnumType ignored -> true;
            case LogicalType.JsonType ignored -> true;
            case LogicalType.BsonType ignored -> true;
            case LogicalType.UuidType ignored -> true;
            case LogicalType.DateType ignored -> true;
            case LogicalType.TimeType ignored -> true;
            case LogicalType.TimestampType ignored -> true;
            case LogicalType.IntType ignored -> true;
            case LogicalType.DecimalType ignored -> true;
            case LogicalType.Float16Type ignored -> true;
            case LogicalType.IntervalType ignored -> false;
            case LogicalType.NullType ignored -> false;
            case LogicalType.VariantType ignored -> false;
            case LogicalType.GeometryType ignored -> false;
            case LogicalType.GeographyType ignored -> false;
            case LogicalType.ListType ignored -> false;
            case LogicalType.MapType ignored -> false;
        };
    }

    /// The order the values of a byte-stored column sort in, as `parquet.thrift`'s `ColumnOrder`
    /// gives it per annotation. It is the one representation of a byte order: the writer collects
    /// bounds in it, a predicate's `Comparison` names one, and `BinaryComparator` compares byte
    /// slices in each that has a slice comparison.
    public enum ByteColumnOrder {
        /// Unsigned byte-wise: the stored bytes order as the values do.
        BYTES,
        /// The number a big-endian two's complement encodes, the shorter value sign-extended: a
        /// `DECIMAL`.
        SIGNED_BIG_ENDIAN,
        /// The count a little-endian two's complement of one width encodes: a
        /// `FIXED_LEN_BYTE_ARRAY(12)` `TIMESTAMP`.
        SIGNED_LITTLE_ENDIAN,
        /// The IEEE half a `FLOAT16` encodes, compared as that float rather than as a slice.
        HALF_FLOAT,
        /// The instant a legacy `INT96` timestamp encodes: whole days, then nanoseconds.
        INT96_INSTANT,
        /// No order: the annotation names none, as [#namesAnOrder] answers.
        NONE
    }

    /// The order the values of a column stored as `INT96`, `BYTE_ARRAY` or
    /// `FIXED_LEN_BYTE_ARRAY` sort in. The writer collects a byte column's bounds in it and the
    /// resolver compares a byte literal in it, so the two cannot disagree about a column.
    ///
    /// The switch is exhaustive rather than a list of the value-ordered annotations, so one added
    /// later has to state whether its values order as their bytes.
    ///
    /// @param type the column's physical type, one stored as bytes
    /// @param annotation the column's annotation, `null` for an unannotated column
    /// @throws IllegalArgumentException if `type` is not stored as bytes, or `annotation` is one
    ///         [#check] refuses over it
    public static ByteColumnOrder byteColumnOrder(PhysicalType type, LogicalType annotation) {
        switch (type) {
            case INT96, BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY -> {
            }
            case BOOLEAN, INT32, INT64, FLOAT, DOUBLE ->
                    throw new IllegalArgumentException(type + " is not stored as bytes");
        }
        // An INT96's twelve bytes are the legacy timestamp's, whatever else annotates it: NULL is
        // the one annotation the grid keeps on one, and it changes nothing a byte literal compares.
        if (type == PhysicalType.INT96) {
            return ByteColumnOrder.INT96_INSTANT;
        }
        if (annotation == null) {
            return ByteColumnOrder.BYTES;
        }
        if (!namesAnOrder(annotation)) {
            return ByteColumnOrder.NONE;
        }
        return switch (annotation) {
            case LogicalType.StringType ignored -> ByteColumnOrder.BYTES;
            case LogicalType.EnumType ignored -> ByteColumnOrder.BYTES;
            case LogicalType.JsonType ignored -> ByteColumnOrder.BYTES;
            case LogicalType.BsonType ignored -> ByteColumnOrder.BYTES;
            // Not in ColumnOrder's list, so the FIXED_LEN_BYTE_ARRAY's own unsigned byte order.
            case LogicalType.UuidType ignored -> ByteColumnOrder.BYTES;
            // "DECIMAL - signed comparison of the represented value"
            case LogicalType.DecimalType ignored -> ByteColumnOrder.SIGNED_BIG_ENDIAN;
            // "FLOAT16 - signed comparison of the represented value"
            case LogicalType.Float16Type ignored -> ByteColumnOrder.HALF_FLOAT;
            // "signed two's-complement comparison of the represented value"
            case LogicalType.TimestampType ignored -> ByteColumnOrder.SIGNED_LITTLE_ENDIAN;
            case LogicalType.IntType ignored -> throw notStoredAsBytes(type, annotation);
            case LogicalType.DateType ignored -> throw notStoredAsBytes(type, annotation);
            case LogicalType.TimeType ignored -> throw notStoredAsBytes(type, annotation);
            // namesAnOrder has answered these above.
            case LogicalType.IntervalType ignored -> throw orderAnsweredAbove(annotation);
            case LogicalType.GeometryType ignored -> throw orderAnsweredAbove(annotation);
            case LogicalType.GeographyType ignored -> throw orderAnsweredAbove(annotation);
            case LogicalType.NullType ignored -> throw orderAnsweredAbove(annotation);
            case LogicalType.VariantType ignored -> throw orderAnsweredAbove(annotation);
            case LogicalType.ListType ignored -> throw orderAnsweredAbove(annotation);
            case LogicalType.MapType ignored -> throw orderAnsweredAbove(annotation);
        };
    }

    /// Whether an integer column's values compare unsigned: only the unsigned `INT` annotations
    /// do. The narrower ones never diverge from the signed order over the values they hold, but
    /// take the unsigned form too, so the annotation alone decides.
    public static boolean ordersUnsigned(LogicalType annotation) {
        return annotation instanceof LogicalType.IntType integer && !integer.isSigned();
    }

    private static IllegalArgumentException notStoredAsBytes(PhysicalType type, LogicalType annotation) {
        return new IllegalArgumentException(annotation + " is not defined over " + type);
    }

    private static IllegalStateException orderAnsweredAbove(LogicalType annotation) {
        return new IllegalStateException(annotation + " names no order, which namesAnOrder answers");
    }

    /// Whether the format defines `annotation` over a column of this physical type and width.
    ///
    /// A `FIXED_LEN_BYTE_ARRAY` that declares no width, or one that is not positive, is not the
    /// annotation's fault: that column cannot be decoded at all and [FixedWidthValidator] refuses
    /// it by name, so a width-fixing annotation over it is classified as though the width matched.
    ///
    /// @param type the column's physical type
    /// @param typeLength the `FIXED_LEN_BYTE_ARRAY` width, `null` for any other type
    /// @param annotation the column's annotation, `null` for an unannotated column
    public static Pairing check(PhysicalType type, Integer typeLength, LogicalType annotation) {
        if (annotation == null) {
            return LEGAL;
        }
        return switch (annotation) {
            // "may only be used to annotate the BYTE_ARRAY primitive type"
            case LogicalType.StringType ignored -> byteArray(type);
            // "annotates the BYTE_ARRAY primitive type"
            case LogicalType.EnumType ignored -> byteArray(type);
            // "must annotate a BYTE_ARRAY primitive type"
            case LogicalType.JsonType ignored -> byteArray(type);
            case LogicalType.BsonType ignored -> byteArray(type);
            // "Allowed for physical type: BYTE_ARRAY." on both structs, the payload being WKB.
            case LogicalType.GeometryType ignored -> byteArray(type);
            case LogicalType.GeographyType ignored -> byteArray(type);
            // "annotates a 16-byte FIXED_LEN_BYTE_ARRAY primitive type"
            case LogicalType.UuidType ignored -> fixedWidth(type, typeLength, FixedWidths.UUID);
            // "must annotate a FIXED_LEN_BYTE_ARRAY of length 12"
            case LogicalType.IntervalType ignored ->
                    fixedWidth(type, typeLength, FixedWidths.INTERVAL);
            // "The primitive type is a 2-byte FIXED_LEN_BYTE_ARRAY."
            case LogicalType.Float16Type ignored ->
                    fixedWidth(type, typeLength, FixedWidths.FLOAT16);
            // "must annotate an int32 that stores the number of days from the Unix epoch"
            case LogicalType.DateType ignored -> only(type, PhysicalType.INT32, INT32_ONLY);
            // MILLIS "must annotate an int32"; MICROS and NANOS "must annotate an int64".
            case LogicalType.TimeType time -> time.unit() == LogicalType.TimeUnit.MILLIS
                    ? only(type, PhysicalType.INT32, INT32_ONLY)
                    : only(type, PhysicalType.INT64, INT64_ONLY);
            case LogicalType.IntType integer -> integerPairing(type, integer);
            case LogicalType.TimestampType ignored -> timestampPairing(type, typeLength);
            case LogicalType.DecimalType decimal -> decimalPairing(type, typeLength, decimal);
            // "allowed for any physical type, only null values stored": no physical type
            // contradicts it, and the schema alone cannot disprove the claim.
            case LogicalType.NullType ignored -> LEGAL;
            // `LIST` and `MAP` annotate a multi-level structure and `VARIANT` "must annotate a
            // group", so none of the three is a pairing a primitive column can hold.
            case LogicalType.ListType ignored -> new Illegal(new GroupAnnotation());
            case LogicalType.MapType ignored -> new Illegal(new GroupAnnotation());
            case LogicalType.VariantType ignored -> new Illegal(new GroupAnnotation());
        };
    }

    /// Whether the format defines the legacy `converted_type` over a column of this physical type,
    /// beyond what [#check] answers for the logical type it stands for.
    ///
    /// Every converted type is defined over the physical types of its logical counterpart but two:
    /// `TIMESTAMP_MILLIS` and `TIMESTAMP_MICROS` "must annotate an int64", while the `TIMESTAMP`
    /// they stand for is also carried by a `FIXED_LEN_BYTE_ARRAY(12)`. The reader drops such a
    /// legacy annotation standing alone on anything else, and the writer writes a `TIMESTAMP` over
    /// a `FIXED_LEN_BYTE_ARRAY(12)` without one.
    ///
    /// @param type the column's physical type
    /// @param converted the column's converted type
    public static Pairing checkConverted(PhysicalType type, ConvertedType converted) {
        return switch (converted) {
            // "Like the logical type counterpart, it must annotate an int64."
            case TIMESTAMP_MILLIS, TIMESTAMP_MICROS -> only(type, PhysicalType.INT64, INT64_ONLY);
            case UTF8, MAP, MAP_KEY_VALUE, LIST, ENUM, DECIMAL, DATE, TIME_MILLIS, TIME_MICROS,
                 UINT_8, UINT_16, UINT_32, UINT_64, INT_8, INT_16, INT_32, INT_64, JSON, BSON,
                 INTERVAL -> LEGAL;
        };
    }

    private static Pairing byteArray(PhysicalType type) {
        return only(type, PhysicalType.BYTE_ARRAY, BYTE_ARRAY_ONLY);
    }

    private static Pairing only(PhysicalType actual, PhysicalType required, List<PhysicalType> allowed) {
        return actual == required ? LEGAL : new Illegal(new WrongPhysicalType(allowed));
    }

    /// Whether a `FIXED_LEN_BYTE_ARRAY` declares a width its values can have. One that does not is
    /// [FixedWidthValidator]'s to refuse, whatever annotates it.
    private static boolean hasUsableWidth(Integer typeLength) {
        return typeLength != null && typeLength > 0;
    }

    /// An annotation parquet-format fixes to one `FIXED_LEN_BYTE_ARRAY` width.
    private static Pairing fixedWidth(PhysicalType type, Integer typeLength, int width) {
        if (type != PhysicalType.FIXED_LEN_BYTE_ARRAY) {
            return new Illegal(new WrongPhysicalType(FIXED_ONLY));
        }
        return !hasUsableWidth(typeLength) || typeLength == width ? LEGAL : new Illegal(new WrongWidth(width));
    }

    /// "INT(8, true), INT(16, true), and INT(32, true) must annotate an int32 primitive type and
    /// INT(64, true) must annotate an int64", and the same for the unsigned forms. Signedness decides
    /// the order the column's values compare in, not which type carries them.
    private static Pairing integerPairing(PhysicalType type, LogicalType.IntType integer) {
        boolean wide = integer.bitWidth() == 64;
        PhysicalType required = wide ? PhysicalType.INT64 : PhysicalType.INT32;
        return type == required ? LEGAL : new Illegal(new WrongPhysicalType(wide ? INT64_ONLY : INT32_ONLY));
    }

    /// "each value is an int64 or a 12-byte FIXED_LEN_BYTE_ARRAY", the latter a little-endian count
    /// whose range the `INT64` form cannot hold.
    private static Pairing timestampPairing(PhysicalType type, Integer typeLength) {
        if (type == PhysicalType.FIXED_LEN_BYTE_ARRAY) {
            return !hasUsableWidth(typeLength) || typeLength == FixedWidths.FLBA12_TIMESTAMP
                    ? LEGAL
                    : new Illegal(new WrongWidth(FixedWidths.FLBA12_TIMESTAMP));
        }
        return only(type, PhysicalType.INT64, TIMESTAMP_TYPES);
    }

    /// "DECIMAL can be used to annotate the following types: int32, for 1 <= precision <= 9;
    /// int64, for 1 <= precision <= 18; fixed_len_byte_array, precision is limited by the array
    /// size, length n can store <= floor(log_10(2^(8*n - 1) - 1)) base-10 digits; byte_array,
    /// precision is not limited". [#maxDecimalPrecision(PhysicalType)] and [#maxDecimalPrecision(int)]
    /// count those digits.
    private static Pairing decimalPairing(PhysicalType type, Integer typeLength,
            LogicalType.DecimalType decimal) {
        boolean held = switch (type) {
            case INT32, INT64, BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY -> true;
            case BOOLEAN, INT96, FLOAT, DOUBLE -> false;
        };
        if (!held) {
            return new Illegal(new WrongPhysicalType(DECIMAL_TYPES));
        }
        // A FIXED_LEN_BYTE_ARRAY with no usable width has no digits to count.
        if (type == PhysicalType.FIXED_LEN_BYTE_ARRAY && !hasUsableWidth(typeLength)) {
            return LEGAL;
        }
        long maxPrecision = type == PhysicalType.FIXED_LEN_BYTE_ARRAY
                ? maxDecimalPrecision(typeLength.intValue())
                : maxDecimalPrecision(type);
        return decimal.precision() > maxPrecision ? new Illegal(new PrecisionTooLarge(decimal.precision(), maxPrecision)) : LEGAL;
    }

    /// The most digits a `DECIMAL` stored in `type` can have: 9 for an `INT32` and 18 for an
    /// `INT64`; a `BYTE_ARRAY` is unbounded. A `FIXED_LEN_BYTE_ARRAY`'s depend on its width, which
    /// [#maxDecimalPrecision(int)] counts.
    ///
    /// @param type the physical type the `DECIMAL` is stored in
    /// @return the largest precision `type` holds; `Long.MAX_VALUE` for a `BYTE_ARRAY`
    /// @throws IllegalArgumentException if `type` does not store a `DECIMAL`, or is a
    ///         `FIXED_LEN_BYTE_ARRAY`
    public static long maxDecimalPrecision(PhysicalType type) {
        return switch (type) {
            case INT32 -> 9;
            case INT64 -> 18;
            case BYTE_ARRAY -> Long.MAX_VALUE;
            case FIXED_LEN_BYTE_ARRAY -> throw new IllegalArgumentException(
                    "A FIXED_LEN_BYTE_ARRAY DECIMAL holds the digits of its width, not of its type");
            case BOOLEAN, INT96, FLOAT, DOUBLE ->
                    throw new IllegalArgumentException("DECIMAL is not stored in " + type);
        };
    }

    /// The most digits a `DECIMAL` stored in a `FIXED_LEN_BYTE_ARRAY` of `fixedWidth` bytes can
    /// have, the largest precision a two's-complement value of that width represents:
    /// `floor(log10(2^(8 * fixedWidth - 1) - 1))`. No power of two is a power of ten, so that is
    /// `floor((8 * fixedWidth - 1) * log10(2))`, which [#LOG10_2] computes without building the
    /// power, whose size a footer's `type_length` would otherwise set.
    ///
    /// @throws IllegalArgumentException if `fixedWidth` is not positive
    public static long maxDecimalPrecision(int fixedWidth) {
        if (fixedWidth <= 0) {
            throw new IllegalArgumentException(
                    "A FIXED_LEN_BYTE_ARRAY DECIMAL needs a positive width, not " + fixedWidth);
        }
        return new BigDecimal(8L * fixedWidth - 1).multiply(LOG10_2).longValue();
    }
}
