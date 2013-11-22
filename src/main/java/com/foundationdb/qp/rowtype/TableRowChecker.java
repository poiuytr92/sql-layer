/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.qp.rowtype;

import com.foundationdb.ais.model.TableName;
import com.foundationdb.ais.model.Table;
import com.foundationdb.qp.row.Row;
import com.foundationdb.server.error.InvalidOperationException;
import com.foundationdb.server.error.NotNullViolationException;

import java.util.BitSet;

public class TableRowChecker implements ConstraintChecker
{
    @Override
    public void checkConstraints(Row row)
    {
        for(int f = notNull.nextSetBit(0); f >= 0; f = notNull.nextSetBit(f+1)) {
            // Delicate: Hidden columns aren't populated until much later.
            if(isNull(row, f) && !table.getColumnsIncludingInternal().get(f).isAkibanPKColumn()) {
                TableName tableName = table.getName();
                throw new NotNullViolationException(tableName.getSchemaName(),
                                                    tableName.getTableName(),
                                                    table.getColumnsIncludingInternal().get(f).getName());
            }
        }
    }

    private boolean isNull(Row row, int f) {
        return row.value(f).isNull();
    }

    public TableRowChecker(RowType rowType)
    {
        this(getTable(rowType));
    }

    public TableRowChecker(Table table)
    {
        this.table = table;
        this.notNull = table.notNull();
    }

    private static Table getTable(RowType rowType) {
        assert rowType.hasTable() : rowType;
        return rowType.table();
    }

    private final Table table;
    private final BitSet notNull;
}
