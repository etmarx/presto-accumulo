package bloomberg.presto.accumulo.index;

import bloomberg.presto.accumulo.AccumuloTable;
import bloomberg.presto.accumulo.model.AccumuloColumnHandle;
import com.facebook.presto.spi.SchemaTableName;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.iterators.LongCombiner;
import org.apache.accumulo.core.iterators.TypedValueCombiner;
import org.apache.accumulo.core.iterators.user.SummingCombiner;
import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.io.Text;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static java.nio.ByteBuffer.wrap;

public class Utils
{
    public static final ByteBuffer METRICS_TABLE_ROW_ID = wrap("METRICS_TABLE".getBytes());
    public static final ByteBuffer METRICS_TABLE_NUM_ROWS_COLUMN_FAMILY = wrap("rows".getBytes());
    public static final byte[] METRICS_COLUMN_QUALIFIER = "cardinality".getBytes();

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte UNDERSCORE = '_';
    private static final TypedValueCombiner.Encoder<Long> ENCODER = new LongCombiner.StringEncoder();

    private Utils()
    {}

    public static void indexMutation(final Mutation m, final Map<ByteBuffer, Set<ByteBuffer>> indexColumns, final Collection<Mutation> indexMutations, final Map<ByteBuffer, Map<ByteBuffer, AtomicLong>> metrics)
    {
        metrics.get(METRICS_TABLE_ROW_ID).get(METRICS_TABLE_NUM_ROWS_COLUMN_FAMILY).incrementAndGet();

        // for each column update in this mutation
        for (ColumnUpdate cu : m.getUpdates()) {
            // get the column qualifiers we want to index for this column family
            // (if any)
            ByteBuffer cf = wrap(cu.getColumnFamily());
            Set<ByteBuffer> indexCQs = indexColumns.get(cf);

            // if we have column qualifiers we want to index for this column
            // family
            if (indexCQs != null) {
                // check if we want to index this particular qualifier
                ByteBuffer cq = wrap(cu.getColumnQualifier());
                if (indexCQs.contains(cq)) {
                    // Row ID = column value
                    // Column Family = columnqualifier_columnfamily
                    // Column Qualifier = row ID
                    // Value = empty

                    ByteBuffer idxRow = wrap(cu.getValue());
                    ByteBuffer idxCF = Utils.getIndexColumnFamily(cu.getColumnFamily(), cu.getColumnQualifier());

                    // create the mutation and add it to the given collection
                    Mutation mIdx = new Mutation(idxRow.array());
                    mIdx.put(idxCF.array(), m.getRow(), EMPTY_BYTES);
                    indexMutations.add(mIdx);

                    // Increment the metrics for this batch of index mutations
                    if (!metrics.containsKey(idxRow)) {
                        metrics.put(idxRow, new HashMap<>());
                    }

                    Map<ByteBuffer, AtomicLong> counter = metrics.get(idxRow);
                    if (!counter.containsKey(idxCF)) {
                        counter.put(idxCF, new AtomicLong(0));
                    }

                    counter.get(idxCF).incrementAndGet();
                }
            }
        }
    }

    public static Map<ByteBuffer, Set<ByteBuffer>> getMapOfIndexedColumns(List<AccumuloColumnHandle> columns)
    {
        Map<ByteBuffer, Set<ByteBuffer>> indexColumns = new HashMap<>();

        for (AccumuloColumnHandle col : columns.stream().filter(x -> x.isIndexed()).collect(Collectors.toList())) {
            ByteBuffer cf = wrap(col.getColumnFamily().getBytes());
            Set<ByteBuffer> qualifies = indexColumns.get(cf);
            if (qualifies == null) {
                qualifies = new HashSet<>();
                indexColumns.put(cf, qualifies);
            }
            qualifies.add(wrap(col.getColumnQualifier().getBytes()));
        }

        return indexColumns;
    }

    public static IteratorSetting getMetricIterator()
    {
        return new IteratorSetting(Integer.MAX_VALUE, SummingCombiner.class, ImmutableMap.of("all", "true", "type", "STRING"));
    }

    public static ByteBuffer getIndexColumnFamily(byte[] columnFamily, byte[] columnQualifier)
    {
        return wrap(ArrayUtils.addAll(ArrayUtils.add(columnFamily, UNDERSCORE), columnQualifier));
    }

    public static String getIndexTableName(String schema, String table)
    {
        return schema.equals("default") ? table + "_idx" : schema + '.' + table + "_idx";
    }

    public static String getIndexTableName(SchemaTableName stName)
    {
        return getIndexTableName(stName.getSchemaName(), stName.getTableName());
    }

    public static Map<String, Set<Text>> getLocalityGroups(AccumuloTable table)
    {
        Map<String, Set<Text>> groups = new HashMap<>();
        for (AccumuloColumnHandle acc : table.getColumns().stream().filter(x -> x.isIndexed()).collect(Collectors.toList())) {
            Text indexColumnFamily = new Text(acc.getColumnFamily() + "_" + acc.getColumnQualifier());
            groups.put(indexColumnFamily.toString(), ImmutableSet.of(indexColumnFamily));
        }
        return groups;
    }

    public static Map<ByteBuffer, Map<ByteBuffer, AtomicLong>> getMetricsDataStructure()
    {
        Map<ByteBuffer, Map<ByteBuffer, AtomicLong>> metrics = new HashMap<>();
        Map<ByteBuffer, AtomicLong> cfMap = new HashMap<>();
        cfMap.put(METRICS_TABLE_NUM_ROWS_COLUMN_FAMILY, new AtomicLong(0));
        metrics.put(METRICS_TABLE_ROW_ID, cfMap);
        return metrics;
    }

    public static Collection<Mutation> getMetricsMutations(final Map<ByteBuffer, Map<ByteBuffer, AtomicLong>> metrics)
    {
        List<Mutation> muts = new ArrayList<>();
        for (Entry<ByteBuffer, Map<ByteBuffer, AtomicLong>> m : metrics.entrySet()) {
            ByteBuffer idxRow = m.getKey();
            // create new mutation
            Mutation mut = new Mutation(idxRow.array());
            for (Entry<ByteBuffer, AtomicLong> columnValues : m.getValue().entrySet()) {
                mut.put(columnValues.getKey().array(), METRICS_COLUMN_QUALIFIER, ENCODER.encode(columnValues.getValue().get()));
            }
            muts.add(mut);
        }

        return muts;
    }

    public static String getMetricsTableName(String schema, String table)
    {
        return schema.equals("default") ? table + "_idx_metrics" : schema + '.' + table + "_idx_metrics";
    }

    public static String getMetricsTableName(SchemaTableName stName)
    {
        return getMetricsTableName(stName.getSchemaName(), stName.getTableName());
    }
}
