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

package com.foundationdb.sql.optimizer.plan;

import com.foundationdb.ais.model.Table;

import java.util.List;
import java.util.ArrayList;

/** A table from AIS.
 */
public class TableNode extends TableTreeBase.TableNodeBase<TableNode> 
{
    private TableTree tree;
    private List<TableSource> uses;
    private long branches;

    public TableNode(Table table, TableTree tree) {
        super(table);
        this.tree = tree;
        uses = new ArrayList<>();
    }

    public TableTree getTree() {
        return tree;
    }

    public void addUse(TableSource use) {
        uses.add(use);
    }

    public long getBranches() {
        return branches;
    }
    public void setBranches(long branches) {
        this.branches = branches;
    }

}
