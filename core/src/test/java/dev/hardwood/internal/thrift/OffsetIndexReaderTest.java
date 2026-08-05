/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.OffsetIndex;

import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_I32;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_I64;
import static dev.hardwood.internal.thrift.ThriftStructBuilder.TYPE_LIST;
import static org.assertj.core.api.Assertions.assertThat;

class OffsetIndexReaderTest {

    @Test
    void readsPageLocationsAndUnencodedSizes() throws IOException {
        byte[] thrift = struct()
                .field(1, TYPE_LIST).structList(pageLocation(100, 40, 0), pageLocation(140, 60, 8))
                .field(2, TYPE_LIST).i64List(512, 768)
                .stop().build();

        OffsetIndex index = read(thrift);

        assertThat(index.pageLocations()).hasSize(2);
        assertThat(index.pageLocations().get(1).offset()).isEqualTo(140L);
        assertThat(index.pageLocations().get(1).compressedPageSize()).isEqualTo(60);
        assertThat(index.pageLocations().get(1).firstRowIndex()).isEqualTo(8L);
        assertThat(index.unencodedByteArrayDataBytes()).containsExactly(512L, 768L);
    }

    @Test
    void reportsOmittedUnencodedSizesAsNull() throws IOException {
        // Field 2 is optional and only defined for BYTE_ARRAY data, so a writer leaves it
        // unset on every other column. That must not read back as a zero-length list.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).structList(pageLocation(100, 40, 0))
                .stop().build();

        assertThat(read(thrift).unencodedByteArrayDataBytes()).isNull();
    }

    @Test
    void readsPresentButEmptyUnencodedSizes() throws IOException {
        byte[] thrift = struct()
                .field(1, TYPE_LIST).structList(pageLocation(100, 40, 0))
                .field(2, TYPE_LIST).i64List()
                .stop().build();

        assertThat(read(thrift).unencodedByteArrayDataBytes()).isEmpty();
    }

    @Test
    void skipsWrongTypedUnencodedSizesField() throws IOException {
        // A malformed file types field 2 as an i64 rather than a list.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).structList(pageLocation(100, 40, 0))
                .field(2, TYPE_I64).i64(4096)
                .field(3, TYPE_I64).i64(7)
                .stop().build();

        OffsetIndex index = read(thrift);

        assertThat(index.unencodedByteArrayDataBytes()).isNull();
        assertThat(index.pageLocations()).hasSize(1);
    }

    @Test
    void skipsStructElementsAtUnencodedSizesFieldWithoutDesync() throws IOException {
        // Field 2 typed list<struct>. Decoding those bytes as varints would leave the
        // cursor mid-struct, so the element type has to drive the skip.
        byte[] thrift = struct()
                .field(1, TYPE_LIST).structList(pageLocation(100, 40, 0))
                .field(2, TYPE_LIST).structList(pageLocation(1, 2, 3), pageLocation(4, 5, 6))
                .field(3, TYPE_I64).i64(7)
                .stop().build();

        OffsetIndex index = read(thrift);

        assertThat(index.unencodedByteArrayDataBytes()).isNull();
        assertThat(index.pageLocations()).hasSize(1);
    }

    /// A `PageLocation` struct body: offset, compressed_page_size, first_row_index.
    private static byte[] pageLocation(long offset, int compressedPageSize, long firstRowIndex) {
        return struct()
                .field(1, TYPE_I64).i64(offset)
                .field(2, TYPE_I32).i32(compressedPageSize)
                .field(3, TYPE_I64).i64(firstRowIndex)
                .stop().build();
    }

    private static OffsetIndex read(byte[] thrift) throws IOException {
        return OffsetIndexReader.read(new ThriftCompactReader(ByteBuffer.wrap(thrift)));
    }

    private static ThriftStructBuilder struct() {
        return new ThriftStructBuilder();
    }
}
