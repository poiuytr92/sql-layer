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

package com.akiban.qp.physicaloperator;

import com.akiban.qp.row.Row;
import com.akiban.qp.row.RowHolder;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.expression.Expression;
import com.akiban.server.expression.ExpressionEvaluation;
import com.akiban.server.types.extract.Extractors;
import com.akiban.util.ArgumentValidation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

class Select_HKeyOrdered extends PhysicalOperator
{
    // Object interface

    @Override
    public String toString()
    {
        return String.format("%s(%s, %s)", getClass().getSimpleName(), predicateRowType, predicate);
    }

    // PhysicalOperator interface


    @Override
    public void findDerivedTypes(Set<RowType> derivedTypes)
    {
        inputOperator.findDerivedTypes(derivedTypes);
    }

    @Override
    protected Cursor cursor(StoreAdapter adapter)
    {
        return new Execution(adapter, inputOperator.cursor(adapter));
    }

    @Override
    public List<PhysicalOperator> getInputOperators() 
    {
        List<PhysicalOperator> result = new ArrayList<PhysicalOperator>(1);
        result.add(inputOperator);
        return result;
    }

    @Override
    public String describePlan()
    {
        return describePlan(inputOperator);
    }

    // Select_HKeyOrdered interface

    public Select_HKeyOrdered(PhysicalOperator inputOperator, RowType predicateRowType, Expression predicate)
    {
        ArgumentValidation.notNull("predicateRowType", predicateRowType);
        ArgumentValidation.notNull("predicate", predicate);
        this.inputOperator = inputOperator;
        this.predicateRowType = predicateRowType;
        this.predicate = predicate;
    }

    // Object state

    private final PhysicalOperator inputOperator;
    private final RowType predicateRowType;
    private final Expression predicate;

    // Inner classes

    private class Execution implements Cursor
    {
        // Cursor interface

        @Override
        public void open(Bindings bindings)
        {
            input.open(bindings);
            this.evaluation.of(bindings);
        }

        @Override
        public Row next()
        {
            Row row = null;
            Row inputRow = input.next();
            while (row == null && inputRow != null) {
                if (inputRow.rowType() == predicateRowType) {
                    evaluation.of(inputRow);
                    if (Extractors.extractBoolean(evaluation.eval())) {
                        // New row of predicateRowType
                        selectedRow.set(inputRow);
                        row = inputRow;
                    }
                } else if (predicateRowType.ancestorOf(inputRow.rowType())) {
                    // Row's type is a descendent of predicateRowType.
                    if (selectedRow.isNotNull() && selectedRow.get().ancestorOf(inputRow)) {
                        row = inputRow;
                    } else {
                        selectedRow.set(null);
                    }
                } else {
                    row = inputRow;
                }
                if (row == null) {
                    inputRow = input.next();
                }
            }
            return row;
        }

        @Override
        public void close()
        {
            selectedRow.set(null);
            input.close();
        }

        // Execution interface

        Execution(StoreAdapter adapter, Cursor input)
        {
            this.input = input;
            this.evaluation = predicate.evaluation();
        }

        // Object state

        private final Cursor input;
        private final RowHolder<Row> selectedRow = new RowHolder<Row>(); // The last input row with type = predicateRowType.
        private final ExpressionEvaluation evaluation;
    }
}
