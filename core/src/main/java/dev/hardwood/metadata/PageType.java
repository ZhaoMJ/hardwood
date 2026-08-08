/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.metadata;

/// The kind of page stored in a column chunk.
///
/// @see <a href="https://parquet.apache.org/docs/file-format/data-pages/">File Format – Data Pages</a>
/// @see <a href="https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift">parquet.thrift</a>
public enum PageType {
    DATA_PAGE,
    INDEX_PAGE,
    DICTIONARY_PAGE,
    DATA_PAGE_V2,
    /// Placeholder for a page type found in metadata that is not recognized.
    /// Reported only through [ColumnMetaData#encodingStats()], whose counts are informational; a page whose own header declares an unrecognized type is
    /// rejected when read, since it cannot be decoded.
    UNKNOWN
}
