/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.conversion;

/// The byte widths parquet-format fixes for a value: those of the `FIXED_LEN_BYTE_ARRAY`
/// annotations, which `AnnotationPairings` checks a column's declared width against, and that of
/// the legacy `INT96`. Every encoder, decoder, check and rendering of one of these values reads
/// its width from here.
public final class FixedWidths {

    /// "`UUID` annotates a 16-byte `FIXED_LEN_BYTE_ARRAY` primitive type."
    public static final int UUID = 16;

    /// `INTERVAL` "must annotate a `fixed_len_byte_array` of length 12": months, days and
    /// milliseconds, each a little-endian unsigned 4-byte integer.
    public static final int INTERVAL = 12;

    /// `FLOAT16`: "The primitive type is a 2-byte `FIXED_LEN_BYTE_ARRAY`."
    public static final int FLOAT16 = 2;

    /// The `FIXED_LEN_BYTE_ARRAY` carrier of a `TIMESTAMP`, "with `type_length = 12`": a signed
    /// 96-bit little-endian count of the unit since the epoch.
    public static final int FLBA12_TIMESTAMP = 12;

    /// The legacy `INT96` timestamp: eight little-endian bytes of nanoseconds of the day, then
    /// four of the Julian day.
    public static final int INT96 = 12;

    private FixedWidths() {
    }
}
