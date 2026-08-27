/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.Encoding;
import dev.hardwood.metadata.FieldPath;
import dev.hardwood.metadata.PageLocation;
import dev.hardwood.metadata.PhysicalType;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression tests for issue #1037: the backwards dictionary extension in
/// [RowGroupIterator#coalescePages] must apply the same `PAGE_COALESCE_GAP_BYTES`
/// threshold as every forward merge, so that a large zstd-encoded chunk with
/// surviving pages near the tail does not trigger a backwards extension across
/// the entire chunk.
///
/// Without the fix, a file whose surviving pages sit near the tail of a large
/// compressed column chunk triggers a backwards extension across the entire chunk,
/// causing severe over-fetch even when the page filter's selection window is
/// correctly sized.
class BoundedDictBackwardsExtensionTest {

    /// 1 MiB threshold — must match `PAGE_COALESCE_GAP_BYTES` in [RowGroupIterator].
    private static final long COALESCE_GAP = 1024L * 1024L;

    /// When the gap between the dictionary page and the first surviving data page
    /// exceeds the threshold, the backwards extension must NOT happen. The group
    /// must start at the data page, not the dictionary.
    @Test
    void backwardsExtensionIsSkippedWhenGapExceedsThreshold() throws Exception {
        long dictOffset = 100L;
        long dataPageOffset = dictOffset + COALESCE_GAP + 1;  // one byte over the limit
        int pageSize = 1000;

        ColumnChunk chunk = chunkWithDict(dictOffset, dataPageOffset);
        List<Object> neededPages = neededPages(new PageLocation(dataPageOffset, pageSize, 0));

        List<?> groups = invokeCoalescePages(neededPages, chunk, dataPageOffset);

        assertThat(groups).hasSize(1);
        long groupOffset = groupOffset(groups.get(0));
        assertThat(groupOffset)
                .as("backwards extension skipped: group should start at the data page offset "
                        + "(%d), not the dictionary offset (%d)", dataPageOffset, dictOffset)
                .isEqualTo(dataPageOffset);
    }

    /// When the gap between the dictionary page and the first surviving data page
    /// is within the threshold, the backwards extension SHOULD happen (existing behaviour
    /// is preserved).
    @Test
    void backwardsExtensionAppliedWhenGapIsWithinThreshold() throws Exception {
        long dictOffset = 100L;
        long dataPageOffset = dictOffset + COALESCE_GAP / 2;  // well within limit
        int pageSize = 1000;

        ColumnChunk chunk = chunkWithDict(dictOffset, dataPageOffset);
        List<Object> neededPages = neededPages(new PageLocation(dataPageOffset, pageSize, 0));

        List<?> groups = invokeCoalescePages(neededPages, chunk, dataPageOffset);

        assertThat(groups).hasSize(1);
        long groupOffset = groupOffset(groups.get(0));
        assertThat(groupOffset)
                .as("backwards extension applied: group should start at the dictionary offset "
                        + "(%d), not the data page offset (%d)", dictOffset, dataPageOffset)
                .isEqualTo(dictOffset);
    }

    /// When the gap is exactly at the threshold (equal), the backwards extension SHOULD happen —
    /// the condition is `<= PAGE_COALESCE_GAP_BYTES`.
    @Test
    void backwardsExtensionAppliedWhenGapIsExactlyAtThreshold() throws Exception {
        long dictOffset = 100L;
        long dataPageOffset = dictOffset + COALESCE_GAP;  // exactly at limit
        int pageSize = 1000;

        ColumnChunk chunk = chunkWithDict(dictOffset, dataPageOffset);
        List<Object> neededPages = neededPages(new PageLocation(dataPageOffset, pageSize, 0));

        List<?> groups = invokeCoalescePages(neededPages, chunk, dataPageOffset);

        assertThat(groups).hasSize(1);
        long groupOffset = groupOffset(groups.get(0));
        assertThat(groupOffset)
                .as("backwards extension applied at exact threshold: group should start at "
                        + "dictionary offset (%d)", dictOffset)
                .isEqualTo(dictOffset);
    }

    /// When there is no dictionary page (`dictStart == 0`), the group starts at the
    /// first needed page regardless of chunk geometry.
    @Test
    void noDictionaryPageLeavesGroupStartUnchanged() throws Exception {
        long dataPageOffset = 1000L;
        int pageSize = 500;

        ColumnChunk chunk = chunkWithoutDict(dataPageOffset);
        List<Object> neededPages = neededPages(new PageLocation(dataPageOffset, pageSize, 0));

        List<?> groups = invokeCoalescePages(neededPages, chunk, dataPageOffset);

        assertThat(groups).hasSize(1);
        long groupOffset = groupOffset(groups.get(0));
        assertThat(groupOffset)
                .as("no dictionary: group starts at the first needed page (%d)", dataPageOffset)
                .isEqualTo(dataPageOffset);
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private static List<Object> neededPages(PageLocation... locations) throws Exception {
        Class<?> neededPageClass = Class.forName(
                "dev.hardwood.internal.reader.RowGroupIterator$NeededPage");
        java.lang.reflect.Constructor<?> ctor = neededPageClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);

        List<Object> pages = new java.util.ArrayList<>();
        for (PageLocation loc : locations) {
            pages.add(ctor.newInstance(loc, PageRowMask.ALL));
        }
        return pages;
    }

    @SuppressWarnings("unchecked")
    private static List<?> invokeCoalescePages(List<Object> neededPages,
                                                ColumnChunk chunk,
                                                long firstDataPageOffset) throws Exception {
        Method method = RowGroupIterator.class.getDeclaredMethod(
                "coalescePages", List.class, ColumnChunk.class, long.class);
        method.setAccessible(true);
        return (List<?>) method.invoke(null, neededPages, chunk, firstDataPageOffset);
    }

    private static long groupOffset(Object pageGroup) throws Exception {
        java.lang.reflect.Field field = pageGroup.getClass().getDeclaredField("offset");
        field.setAccessible(true);
        return (long) field.get(pageGroup);
    }

    private static ColumnChunk chunkWithDict(long dictOffset, long dataPageOffset) {
        ColumnMetaData meta = new ColumnMetaData(
                PhysicalType.INT32,
                List.of(Encoding.PLAIN, Encoding.RLE_DICTIONARY),
                FieldPath.of("test"),
                CompressionCodec.ZSTD,
                /*numValues=*/ 1000L,
                /*totalUncompressed=*/ 50_000L,
                /*totalCompressed=*/ 50_000L,
                Map.of(),
                /*dataPageOffset=*/ dataPageOffset,
                /*dictionaryPageOffset=*/ dictOffset,
                /*statistics=*/ null,
                /*geospatialStatistics=*/ null,
                /*bloomFilterOffset=*/ null,
                /*bloomFilterLength=*/ null,
                /*encodingStats=*/ List.of(),
                /*sizeStatistics=*/ null);
        return new ColumnChunk(meta, null, null, null, null, "");
    }

    private static ColumnChunk chunkWithoutDict(long dataPageOffset) {
        ColumnMetaData meta = new ColumnMetaData(
                PhysicalType.INT32,
                List.of(Encoding.PLAIN),
                FieldPath.of("test"),
                CompressionCodec.UNCOMPRESSED,
                /*numValues=*/ 1000L,
                /*totalUncompressed=*/ 10_000L,
                /*totalCompressed=*/ 10_000L,
                Map.of(),
                /*dataPageOffset=*/ dataPageOffset,
                /*dictionaryPageOffset=*/ null,
                /*statistics=*/ null,
                /*geospatialStatistics=*/ null,
                /*bloomFilterOffset=*/ null,
                /*bloomFilterLength=*/ null,
                /*encodingStats=*/ List.of(),
                /*sizeStatistics=*/ null);
        return new ColumnChunk(meta, null, null, null, null, "");
    }
}
