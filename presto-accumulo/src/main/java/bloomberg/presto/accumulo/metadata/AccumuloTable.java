/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package bloomberg.presto.accumulo.metadata;

import bloomberg.presto.accumulo.index.Indexer;
import bloomberg.presto.accumulo.model.AccumuloColumnHandle;
import bloomberg.presto.accumulo.serializers.AccumuloRowSerializer;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.StandardErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

/**
 * This class encapsulates metadata regarding an Accumulo table in Presto. It is a jackson
 * serializable object.
 */
public class AccumuloTable
{
    private boolean indexed;
    private final boolean external;
    private final String rowId;
    private final String schema;
    private final String table;
    private List<AccumuloColumnHandle> columns;
    private final List<ColumnMetadata> columnsMetadata;
    private final String serializerClassName;
    private final String scanAuths;

    /***
     * Creates a new instance of AccumuloTable
     *
     * @param schema
     *            Presto schema (Accumulo namespace)
     * @param table
     *            Presto table (Accumulo table)
     * @param columns
     *            A list of {@link AccumuloColumnHandle} objects for the table
     * @param rowId
     *            The Presto column name that is the Accumulo row ID
     * @param external
     *            Whether or not this table is external, i.e. Presto only manages metadata
     * @param serializerClassName
     *            The qualified Java class name to (de)serialize data from Accumulo
     * @param scanAuths
     *            Scan-time authorizations of the scanner, or null to use all user scan
     *            authorizations
     */
    @JsonCreator
    public AccumuloTable(@JsonProperty("schema") String schema, @JsonProperty("table") String table,
            @JsonProperty("columns") List<AccumuloColumnHandle> columns,
            @JsonProperty("rowId") String rowId, @JsonProperty("external") boolean external,
            @JsonProperty("serializerClassName") String serializerClassName,
            @JsonProperty("scanAuths") String scanAuths)
    {
        this.external = requireNonNull(external, "external is null");
        this.rowId = requireNonNull(rowId, "rowId is null");
        this.schema = requireNonNull(schema, "schema is null");
        this.table = requireNonNull(table, "table is null");
        this.columns = ImmutableList.copyOf(requireNonNull(columns, "columns are null"));
        this.serializerClassName =
                requireNonNull(serializerClassName, "serializerClassName is null");
        this.scanAuths = scanAuths;

        boolean indexed = false;
        // Extract the ColumnMetadata from the handles for faster access
        ImmutableList.Builder<ColumnMetadata> cmb = ImmutableList.builder();
        for (AccumuloColumnHandle column : this.columns) {
            cmb.add(column.getColumnMetadata());
            indexed |= column.isIndexed();
        }

        this.indexed = indexed;
        this.columnsMetadata = cmb.build();
    }

    /**
     * Gets the row ID.
     *
     * @return Row ID
     */
    @JsonProperty
    public String getRowId()
    {
        return rowId;
    }

    /**
     * Gets the schema name.
     *
     * @return Schema name
     */
    @JsonProperty
    public String getSchema()
    {
        return schema;
    }

    /**
     * Gets the table name.
     *
     * @return Table name
     */
    @JsonProperty
    public String getTable()
    {
        return table;
    }

    /**
     * Gets the full name of the index table.
     *
     * @see Indexer#getIndexTableName
     * @return Index table name
     */
    @JsonIgnore
    public String getIndexTableName()
    {
        return Indexer.getIndexTableName(schema, table);
    }

    /**
     * Gets the full name of the metrics table.
     *
     * @see Indexer#getMetricsTableName
     * @return Metrics table name
     */
    @JsonIgnore
    public String getMetricsTableName()
    {
        return Indexer.getMetricsTableName(schema, table);
    }

    /**
     * Gets the full table name of the Accumulo table, i.e. schemaName.tableName.
     *
     * @see AccumuloTable#getFullTableName
     * @return Full table name
     */
    @JsonIgnore
    public String getFullTableName()
    {
        return getFullTableName(schema, table);
    }

    /**
     * Gets all configured columns of the Accumulo table.
     *
     * @return The list of {@link AccumuloColumnHandle}
     */
    @JsonProperty
    public List<AccumuloColumnHandle> getColumns()
    {
        return columns;
    }

    /**
     * Adds a new column at the specified position, updating the ordinals of all columns if
     * necessary. Will set the 'indexed' flag if this column is indexed but the table was not
     * previously indexed.
     *
     * @param newColumn
     *            New column to add
     * @throws IndexOutOfBoundsException
     *             If the ordinal negative or greater than or equal to the number of columns
     */
    @JsonIgnore
    public void addColumn(AccumuloColumnHandle newColumn)
    {
        final List<AccumuloColumnHandle> newColumns;

        // If this column is going to be appended instead of inserted
        if (newColumn.getOrdinal() == columns.size()) {
            // Validate this column does not already exist
            for (AccumuloColumnHandle col : columns) {
                if (col.getName().equals(newColumn.getName())) {
                    throw new PrestoException(StandardErrorCode.ALREADY_EXISTS,
                            "Column " + newColumn.getName() + " already exists in table");
                }
            }

            // Copy the list and add the new column at the end
            newColumns = new ArrayList<>(columns);
            newColumns.add(newColumn);
        }
        else {
            // Else, iterate through all existing columns, updating the ordinals and inserting the
            // column at the appropriate place
            newColumns = new ArrayList<>();
            int ordinal = 0;
            for (AccumuloColumnHandle col : columns) {
                // Validate this column does not already exist
                if (col.getName().equals(newColumn.getName())) {
                    throw new PrestoException(StandardErrorCode.ALREADY_EXISTS,
                            "Column " + newColumn.getName() + " already exists in table");
                }

                // Add the new column here
                if (ordinal == newColumn.getOrdinal()) {
                    newColumns.add(newColumn);
                    ++ordinal;
                }

                // Update the ordinal and add the already existing column
                col.setOrdinal(ordinal);
                newColumns.add(col);

                ++ordinal;
            }
        }

        // Set the new column list
        columns = ImmutableList.copyOf(newColumns);

        // Update the index status of the table
        indexed |= newColumn.isIndexed();
    }

    /**
     * Gets the configured scan authorizations, or null if not set
     *
     * @return Scan authorizations
     */
    @JsonProperty
    public String getScanAuthorizations()
    {
        return scanAuths;
    }

    /**
     * Gets the configured serializer class name.
     *
     * @return The list of {@link AccumuloColumnHandle}
     */
    @JsonProperty
    public String getSerializerClassName()
    {
        return serializerClassName;
    }

    /**
     * Gets the list of ColumnMetadata from each AccumuloColumnHandle.
     *
     * @return The list of {@link ColumnMetadata}
     */
    @JsonIgnore
    public List<ColumnMetadata> getColumnsMetadata()
    {
        return columnsMetadata;
    }

    /**
     * Gets a Boolean value indicating if the Accumulo tables are external, i.e. Presto only manages
     * metadata.
     *
     * @return True if external, false otherwise
     */
    @JsonProperty
    public boolean isExternal()
    {
        return external;
    }

    /**
     * Gets a Boolean value indicating if the Accumulo tables are indexed
     *
     * @return True if indexed, false otherwise
     */
    @JsonIgnore
    public boolean isIndexed()
    {
        return indexed;
    }

    /**
     * Gets a new instance of the configured {@link AccumuloRowSerializer}
     *
     * @return Class object
     * @throws PrestoException
     *             If the class is not found on the classpath
     */
    @JsonIgnore
    public AccumuloRowSerializer getSerializerInstance()
    {
        try {
            return (AccumuloRowSerializer) Class.forName(serializerClassName).newInstance();
        }
        catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            throw new PrestoException(StandardErrorCode.USER_ERROR,
                    "Configured serializer class not found", e);
        }
    }

    /**
     * Gets the full table name of the Accumulo table, i.e. schemaName.tableName. If the schemaName
     * is 'default', then there is no Accumulo namespace and the table name is all that is returned.
     *
     * @param schema
     *            Schema name
     * @param table
     *            Table name
     * @return Full table name
     */
    @JsonIgnore
    public static String getFullTableName(String schema, String table)
    {
        return schema.equals("default") ? table : schema + '.' + table;
    }

    /**
     * Gets the full table name of the Accumulo table, i.e. schemaName.tableName. If the schemaName
     * is 'default', then there is no Accumulo namespace and the table name is all that is returned.
     *
     * @param stn
     *            SchemaTableName
     * @return Full table name
     */
    @JsonIgnore
    public static String getFullTableName(SchemaTableName stn)
    {
        return getFullTableName(stn.getSchemaName(), stn.getTableName());
    }

    @Override
    public String toString()
    {
        return toStringHelper(this).add("schemaName", schema).add("tableName", table)
                .add("columns", columns).add("rowIdName", rowId).add("external", external)
                .add("serializerClassName", serializerClassName).add("scanAuths", scanAuths)
                .toString();
    }
}
