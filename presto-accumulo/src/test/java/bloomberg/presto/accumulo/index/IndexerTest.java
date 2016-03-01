package bloomberg.presto.accumulo.index;

import bloomberg.presto.accumulo.metadata.AccumuloTable;
import bloomberg.presto.accumulo.model.AccumuloColumnHandle;
import bloomberg.presto.accumulo.serializers.AccumuloRowSerializer;
import bloomberg.presto.accumulo.serializers.LexicoderRowSerializer;
import com.facebook.presto.type.ArrayType;
import com.google.common.collect.ImmutableList;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Iterator;
import java.util.Map.Entry;

import static bloomberg.presto.accumulo.serializers.LexicoderRowSerializer.encode;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;

public class IndexerTest
{
    private static final byte[] AGE = "age".getBytes();
    private static final byte[] CF = "cf".getBytes();
    private static final byte[] FIRSTNAME = "firstname".getBytes();
    private static final byte[] SENDERS = "arr".getBytes();

    private static final byte[] M1_ROWID = encode(VARCHAR, "row1");
    private static final byte[] AGE_VALUE = encode(BIGINT, 27L);
    private static final byte[] M1_FNAME_VALUE = encode(VARCHAR, "alice");
    private static final byte[] M1_ARR_VALUE = encode(new ArrayType(VARCHAR), AccumuloRowSerializer
            .getBlockFromArray(VARCHAR, ImmutableList.of("abc", "def", "ghi")));

    private static final byte[] M2_ROWID = encode(VARCHAR, "row2");
    private static final byte[] M2_FNAME_VALUE = encode(VARCHAR, "bob");
    private static final byte[] M2_ARR_VALUE = encode(new ArrayType(VARCHAR), AccumuloRowSerializer
            .getBlockFromArray(VARCHAR, ImmutableList.of("ghi", "mno", "abc")));

    private Indexer indexer;
    private Instance inst = new MockInstance();
    private Connector conn;

    private Mutation m1 = new Mutation(M1_ROWID);
    private Mutation m2 = new Mutation(M2_ROWID);

    private AccumuloTable table;
    private Authorizations auths;

    @Before
    public void setup()
            throws Exception
    {
        conn = inst.getConnector("root", new PasswordToken(""));

        AccumuloColumnHandle c1 =
                new AccumuloColumnHandle("accumulo", "id", null, null, VARCHAR, 0, "", false);
        AccumuloColumnHandle c2 =
                new AccumuloColumnHandle("accumulo", "age", "cf", "age", BIGINT, 1, "", true);
        AccumuloColumnHandle c3 = new AccumuloColumnHandle("accumulo", "firstname", "cf",
                "firstname", VARCHAR, 2, "", true);
        AccumuloColumnHandle c4 = new AccumuloColumnHandle("accumulo", "arr", "cf", "arr",
                new ArrayType(VARCHAR), 3, "", true);

        table = new AccumuloTable("default", "index_test_table", ImmutableList.of(c1, c2, c3, c4),
                "id", true, LexicoderRowSerializer.class.getCanonicalName());

        conn.tableOperations().create(table.getFullTableName());
        conn.tableOperations().create(table.getIndexTableName());
        conn.tableOperations().create(table.getMetricsTableName());
        for (IteratorSetting s : Indexer.getMetricIterators(table)) {
            conn.tableOperations().attachIterator(table.getMetricsTableName(), s);
        }

        m1.put(CF, AGE, AGE_VALUE);
        m1.put(CF, FIRSTNAME, M1_FNAME_VALUE);
        m1.put(CF, SENDERS, M1_ARR_VALUE);
        m2.put(CF, AGE, AGE_VALUE);
        m2.put(CF, FIRSTNAME, M2_FNAME_VALUE);
        m2.put(CF, SENDERS, M2_ARR_VALUE);

        auths = conn.securityOperations().getUserAuthorizations("root");

        indexer = new Indexer(conn, auths, table, new BatchWriterConfig());
    }

    @After
    public void cleanup()
            throws AccumuloException, AccumuloSecurityException, TableNotFoundException
    {
        conn.tableOperations().delete(table.getFullTableName());
        conn.tableOperations().delete(table.getIndexTableName());
        conn.tableOperations().delete(table.getMetricsTableName());
    }

    @Test
    public void testMutationIndex()
            throws Exception
    {
        indexer.index(m1);
        indexer.flush();

        Scanner scan = conn.createScanner(table.getIndexTableName(), auths);
        scan.setRange(new Range());

        Iterator<Entry<Key, Value>> iter = scan.iterator();
        assertKeyValuePair(iter.next(), AGE_VALUE, "cf_age", "row1", "");
        assertKeyValuePair(iter.next(), "abc".getBytes(), "cf_arr", "row1", "");
        assertKeyValuePair(iter.next(), M1_FNAME_VALUE, "cf_firstname", "row1", "");
        assertKeyValuePair(iter.next(), "def".getBytes(), "cf_arr", "row1", "");
        assertKeyValuePair(iter.next(), "ghi".getBytes(), "cf_arr", "row1", "");
        Assert.assertFalse(iter.hasNext());

        scan.close();

        scan = conn.createScanner(table.getMetricsTableName(), auths);
        scan.setRange(new Range());

        iter = scan.iterator();
        assertKeyValuePair(iter.next(), AGE_VALUE, "cf_age", "___card___", "1");
        assertKeyValuePair(iter.next(), Indexer.METRICS_TABLE_ROW_ID.array(), "___rows___",
                "___card___", "1");
        assertKeyValuePair(iter.next(), Indexer.METRICS_TABLE_ROW_ID.array(), "___rows___",
                "___first_row___", "row1");
        assertKeyValuePair(iter.next(), Indexer.METRICS_TABLE_ROW_ID.array(), "___rows___",
                "___last_row___", "row1");
        assertKeyValuePair(iter.next(), "abc".getBytes(), "cf_arr", "___card___", "1");
        assertKeyValuePair(iter.next(), M1_FNAME_VALUE, "cf_firstname", "___card___", "1");
        assertKeyValuePair(iter.next(), "def".getBytes(), "cf_arr", "___card___", "1");
        assertKeyValuePair(iter.next(), "ghi".getBytes(), "cf_arr", "___card___", "1");
        Assert.assertFalse(iter.hasNext());

        scan.close();

        indexer.index(m2);
        indexer.close();

        scan = conn.createScanner(table.getIndexTableName(), auths);
        scan.setRange(new Range());
        iter = scan.iterator();
        assertKeyValuePair(iter.next(), AGE_VALUE, "cf_age", "row1", "");
        assertKeyValuePair(iter.next(), AGE_VALUE, "cf_age", "row2", "");
        assertKeyValuePair(iter.next(), "abc".getBytes(), "cf_arr", "row1", "");
        assertKeyValuePair(iter.next(), "abc".getBytes(), "cf_arr", "row2", "");
        assertKeyValuePair(iter.next(), M1_FNAME_VALUE, "cf_firstname", "row1", "");
        assertKeyValuePair(iter.next(), M2_FNAME_VALUE, "cf_firstname", "row2", "");
        assertKeyValuePair(iter.next(), "def".getBytes(), "cf_arr", "row1", "");
        assertKeyValuePair(iter.next(), "ghi".getBytes(), "cf_arr", "row1", "");
        assertKeyValuePair(iter.next(), "ghi".getBytes(), "cf_arr", "row2", "");
        assertKeyValuePair(iter.next(), "mno".getBytes(), "cf_arr", "row2", "");
        Assert.assertFalse(iter.hasNext());

        scan.close();

        scan = conn.createScanner(table.getMetricsTableName(), auths);
        scan.setRange(new Range());

        iter = scan.iterator();
        assertKeyValuePair(iter.next(), AGE_VALUE, "cf_age", "___card___", "2");
        assertKeyValuePair(iter.next(), Indexer.METRICS_TABLE_ROW_ID.array(), "___rows___",
                "___card___", "2");
        assertKeyValuePair(iter.next(), Indexer.METRICS_TABLE_ROW_ID.array(), "___rows___",
                "___first_row___", "row1");
        assertKeyValuePair(iter.next(), Indexer.METRICS_TABLE_ROW_ID.array(), "___rows___",
                "___last_row___", "row2");
        assertKeyValuePair(iter.next(), "abc".getBytes(), "cf_arr", "___card___", "2");
        assertKeyValuePair(iter.next(), M1_FNAME_VALUE, "cf_firstname", "___card___", "1");
        assertKeyValuePair(iter.next(), M2_FNAME_VALUE, "cf_firstname", "___card___", "1");
        assertKeyValuePair(iter.next(), "def".getBytes(), "cf_arr", "___card___", "1");
        assertKeyValuePair(iter.next(), "ghi".getBytes(), "cf_arr", "___card___", "2");
        assertKeyValuePair(iter.next(), "mno".getBytes(), "cf_arr", "___card___", "1");
        Assert.assertFalse(iter.hasNext());

        scan.close();
    }

    private void assertKeyValuePair(Entry<Key, Value> e, byte[] row, String cf, String cq,
            String value)
    {
        System.out.println(e);
        Assert.assertArrayEquals(row, e.getKey().getRow().copyBytes());
        Assert.assertEquals(cf, e.getKey().getColumnFamily().toString());
        Assert.assertEquals(cq, e.getKey().getColumnQualifier().toString());
        Assert.assertEquals(value, new String(e.getValue().get()));
    }
}
