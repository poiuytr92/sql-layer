/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.qp.persistitadapter;

import com.akiban.ais.model.*;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.physicaloperator.Cursor;
import com.akiban.qp.physicaloperator.Executable;
import com.akiban.qp.physicaloperator.Limit;
import com.akiban.qp.physicaloperator.PhysicalOperator;
import com.akiban.qp.physicaloperator.ConstantValueBindable;
import com.akiban.qp.row.Row;
import com.akiban.qp.row.RowHolder;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.qp.rowtype.UserTableRowType;
import com.akiban.server.IndexDef;
import com.akiban.server.RowData;
import com.akiban.server.RowDef;
import com.akiban.server.api.dml.ColumnSelector;
import com.akiban.server.api.dml.scan.ScanLimit;
import com.akiban.server.service.memcache.hprocessor.PredicateLimit;
import com.akiban.server.service.session.Session;
import com.akiban.server.store.PersistitStore;
import com.akiban.server.store.RowCollector;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.akiban.qp.physicaloperator.API.*;

public abstract class OperatorBasedRowCollector implements RowCollector
{
    // RowCollector interface

    @Override
    public boolean collectNextRow(ByteBuffer payload) throws Exception
    {
        boolean wroteToPayload = false;
        if (!closed) {
            currentRow.set(cursor.currentRow());
            PersistitGroupRow row = (PersistitGroupRow) currentRow.get();
            if (row == null) {
                close();
            } else {
                RowData rowData = row.rowData();
                try {
                    payload.put(rowData.getBytes(), rowData.getRowStart(), rowData.getRowSize());
                    wroteToPayload = true;
                    rowCount++;
                    if (!cursor.next()) {
                        close();
                    }
                } catch (BufferOverflowException e) {
                    assert !wroteToPayload;
                }
            }
        }
        return wroteToPayload;
    }

    @Override
    public RowData collectNextRow() throws Exception
    {
        RowData rowData = null;
        if (!closed) {
            currentRow.set(cursor.currentRow());
            PersistitGroupRow row = (PersistitGroupRow) currentRow.get();
            if (row == null) {
                close();
            } else {
                rowData = row.rowData();
                rowCount++;
                if (!cursor.next()) {
                    close();
                }
            }
        }
        return rowData;
    }

    @Override
    public boolean hasMore() throws Exception
    {
        return !closed;
    }

    @Override
    public void close()
    {
        if (!closed) {
            currentRow.set(null);
            cursor.close();
            closed = true;
        }
    }

    @Override
    public int getDeliveredRows()
    {
        return rowCount;
    }

    @Override
    public int getDeliveredBuffers()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getDeliveredBytes()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getTableId()
    {
        return predicateType.userTable().getTableId();
    }

    @Override
    public IndexDef getIndexDef()
    {
        return (IndexDef) predicateIndex.indexDef();
    }

    @Override
    public long getId()
    {
        return rowCollectorId;
    }

    @Override
    public void outputToMessage(boolean outputToMessage)
    {
    }

    @Override
    public boolean checksLimit()
    {
        return true;
    }

    // OperatorBasedRowCollector interface

    public static OperatorBasedRowCollector newCollector(Session session,
                                                         PersistitStore store,
                                                         int scanFlags,
                                                         RowDef rowDef,
                                                         int indexId,
                                                         byte[] columnBitMap,
                                                         RowData start,
                                                         ColumnSelector startColumns,
                                                         RowData end,
                                                         ColumnSelector endColumns,
                                                         ScanLimit scanLimit)
    {
        if ((scanFlags & (SCAN_FLAGS_PREFIX | SCAN_FLAGS_SINGLE_ROW)) != 0) {
            throw new IllegalArgumentException
                ("SCAN_FLAGS_PREFIX and SCAN_FLAGS_SINGLE_ROW are unsupported");
        }
        if (start != null && end != null && start.getRowDefId() != end.getRowDefId()) {
            throw new IllegalArgumentException(String.format("start row def id: %s, end row def id: %s",
                                                             start.getRowDefId(), end.getRowDefId()));
        }
        OperatorBasedRowCollector rowCollector =
            rowDef.isUserTable()
            // HAPI query root table = predicate table
            ? new OneTableRowCollector(session,
                                       store,
                                       rowDef,
                                       indexId,
                                       scanFlags,
                                       start,
                                       startColumns,
                                       end,
                                       endColumns)
            // HAPI query root table != predicate table
            : new TwoTableRowCollector(session,
                                       store,
                                       rowDef,
                                       indexId,
                                       scanFlags,
                                       start,
                                       startColumns,
                                       end,
                                       endColumns,
                                       columnBitMap);
        boolean singleRow = (scanFlags & SCAN_FLAGS_SINGLE_ROW) != 0;
        boolean descending = (scanFlags & SCAN_FLAGS_DESCENDING) != 0;
        boolean deep = (scanFlags & SCAN_FLAGS_DEEP) != 0;
        rowCollector.createPlan(scanLimit, singleRow, descending, deep);
        return rowCollector;
    }
    
    protected OperatorBasedRowCollector(PersistitStore store, Session session)
    {
        AkibanInformationSchema ais = store.getRowDefCache().ais();
        this.schema = new Schema(ais);
        this.adapter = new PersistitAdapter(this.schema, store, session);
        this.rowCollectorId = idCounter.getAndIncrement();
    }

    private void createPlan(ScanLimit scanLimit, boolean singleRow, boolean descending, boolean deep)
    {
        // Plan and query
        Executable query;
        Limit limit = new PersistitRowLimit(scanLimit(scanLimit, singleRow));
        boolean useIndex =
            predicateIndex != null && !((IndexDef) predicateIndex.indexDef()).isHKeyEquivalent();
        GroupTable groupTable = queryRootTable.getGroup().getGroupTable();
        PhysicalOperator rootOperator;
        PhysicalOperator restrictionOperator;
        if (useIndex) {
            PhysicalOperator indexScan = indexScan_Default(predicateIndex, descending, ConstantValueBindable.of(indexKeyRange));
            rootOperator = indexLookup_Default(indexScan, groupTable, limit);
            restrictionOperator = indexScan;
        } else {
            PhysicalOperator groupScan = groupScan_Default(groupTable, descending, limit);
            rootOperator = groupScan;
            restrictionOperator = groupScan;
        }
        // Fill in ancestors above predicate
        if (queryRootType != predicateType) {
            rootOperator = ancestorLookup_Default(rootOperator, groupTable, predicateType, ancestorTypes());
        }
        // Get rid of everything above query root table.
        rootOperator = extract_Default(schema, rootOperator, Arrays.<RowType>asList(queryRootType));
        // Get rid of selected types below query root table.
        Set<RowType> cutTypes = cutTypes(deep);
        if (!cutTypes.isEmpty()) {
            rootOperator = cut_Default(schema, rootOperator, cutTypes);
        }
        query = new Executable(adapter, rootOperator).bind(restrictionOperator, indexKeyRange);
        // Executable stuff
        cursor = query.cursor();
        cursor.open();
        closed = !cursor.next();
    }

    private List<RowType> ancestorTypes()
    {
        UserTableRowType queryRootType = schema.userTableRowType(queryRootTable);
        List<RowType> ancestorTypes = new ArrayList<RowType>();
        if (predicateType != null && queryRootType != predicateType) {
            UserTable ancestor = predicateType.userTable();
            do {
                ancestor = ancestor.parentTable();
                ancestorTypes.add(schema.userTableRowType(ancestor));
            } while (ancestor != queryRootType.userTable());
        }
        return ancestorTypes;
    }

    private Set<RowType> cutTypes(boolean deep)
    {
        Set<RowType> cutTypes = new HashSet<RowType>();
        if (!deep) {
            // Find the leafmost tables in requiredUserTables and cut everything below those. It is possible
            // that a column bit map includes, for example, customer and item but not order. This case is NOT
            // handled -- we'll just include (i.e. not cut) customer, order and item.
            Set<UserTable> leafmostRequiredUserTables = new HashSet<UserTable>(requiredUserTables);
            for (UserTable requiredUserTable : requiredUserTables) {
                UserTable ancestor = requiredUserTable.parentTable();
                while (ancestor != null) {
                    leafmostRequiredUserTables.remove(ancestor);
                    ancestor = ancestor.parentTable();
                }
            }
            // Cut below each leafmost required table
            for (UserTable leafmostRequiredUserTable : leafmostRequiredUserTables) {
                for (Join join : leafmostRequiredUserTable.getChildJoins()) {
                    cutTypes.add(schema.userTableRowType(join.getChild()));
                }
            }
        }
        if (predicateType != null) {
            UserTable predicateTable = predicateType.userTable();
            if (predicateTable != queryRootTable) {
                // Cut tables not on the path from the predicate table up to query table
                UserTable table = predicateTable;
                UserTable childOnPath;
                while (table != queryRootTable) {
                    childOnPath = table;
                    table = table.parentTable();
                    for (Join join : table.getChildJoins()) {
                        UserTable child = join.getChild();
                        if (child != childOnPath) {
                            cutTypes.add(schema.userTableRowType(child));
                        }
                    }
                }
            }
        }
        return cutTypes;
    }

    private ScanLimit scanLimit(ScanLimit requestLimit, boolean singleRow)
    {
        ScanLimit limit = requestLimit == null ? ScanLimit.NONE : requestLimit;
        if (limit != ScanLimit.NONE && singleRow) {
            throw new IllegalArgumentException
                ("Cannot specify limit along with SCAN_FLAGS_SINGLE_ROW");
        }
        if (singleRow) {
            limit = new PredicateLimit(predicateType.userTable().getTableId(), 1);
        }
        return limit;
    }

    // Class state

    private static final AtomicLong idCounter = new AtomicLong(0);

    // Object state

    private long rowCollectorId;
    protected PersistitAdapter adapter;
    protected Schema schema;
    protected UserTable queryRootTable;
    protected UserTableRowType queryRootType;
    protected Index predicateIndex;
    protected UserTableRowType predicateType;
    // If we're querying a user table, then requiredUserTables contains just queryRootTable
    // If we're querying a group table, it contains those user tables containing columns in the
    // columnBitMap.
    protected final Set<UserTable> requiredUserTables = new HashSet<UserTable>();
    protected IndexKeyRange indexKeyRange;
    private Cursor cursor;
    private boolean closed;
    private int rowCount = 0;
    private RowHolder<Row> currentRow = new RowHolder<Row>();
}
