/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.calcite.exec.rel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.ignite.internal.processors.query.calcite.exec.ExecutionContext;
import org.apache.ignite.internal.processors.query.calcite.exec.RowHandler;
import org.apache.ignite.internal.processors.query.calcite.exec.RowHandler.RowFactory;
import org.apache.ignite.internal.processors.query.calcite.exec.TableFunctionScan;

/** Execution node for a table function with relational cursor inputs. */
public class TableFunctionScanNode<Row> extends AbstractNode<Row> {
    /** */
    private final List<RelDataType> inputTypes;

    /** */
    private final Function<Row, Iterable<?>> function;

    /** */
    private final RowFactory<Row> argumentsFactory;

    /** */
    private final RowFactory<Row> resultFactory;

    /** */
    private final List<List<Row>> inputs;

    /** Number of rows awaited from every source; {@code -1} means that source has ended. */
    private final int[] waiting;

    /** */
    private int requested;

    /** */
    private Iterator<Row> result;

    /** */
    private boolean inLoop;

    /** */
    public TableFunctionScanNode(
        ExecutionContext<Row> ctx,
        RelDataType rowType,
        List<RelDataType> inputTypes,
        Function<Row, Iterable<?>> function,
        RowFactory<Row> argumentsFactory,
        RowFactory<Row> resultFactory
    ) {
        super(ctx, rowType);

        this.inputTypes = inputTypes;
        this.function = function;
        this.argumentsFactory = argumentsFactory;
        this.resultFactory = resultFactory;

        inputs = new ArrayList<>(inputTypes.size());
        waiting = new int[inputTypes.size()];

        for (int i = 0; i < inputTypes.size(); i++)
            inputs.add(new ArrayList<>());
    }

    /** {@inheritDoc} */
    @Override public void request(int rowsCnt) throws Exception {
        assert rowsCnt > 0 && requested == 0;

        checkState();

        requested = rowsCnt;

        if (result != null) {
            if (!inLoop)
                context().execute(this::flush, this::onError);

            return;
        }

        for (int i = 0; i < sources().size(); i++) {
            if (waiting[i] == 0)
                sources().get(i).request(waiting[i] = IN_BUFFER_SIZE);
        }
    }

    /** {@inheritDoc} */
    @Override protected Downstream<Row> requestDownstream(int idx) {
        return new Downstream<Row>() {
            @Override public void push(Row row) throws Exception {
                TableFunctionScanNode.this.push(row, idx);
            }

            @Override public void end() throws Exception {
                TableFunctionScanNode.this.end(idx);
            }

            @Override public void onError(Throwable e) {
                TableFunctionScanNode.this.onError(e);
            }
        };
    }

    /** */
    private void push(Row row, int idx) throws Exception {
        assert waiting[idx] > 0;

        checkState();

        inputs.get(idx).add(row);

        if (--waiting[idx] == 0)
            sources().get(idx).request(waiting[idx] = IN_BUFFER_SIZE);
    }

    /** */
    private void end(int idx) throws Exception {
        assert waiting[idx] > 0;

        waiting[idx] = -1;

        for (int state : waiting) {
            if (state != -1)
                return;
        }

        ResultSet[] cursors = new ResultSet[inputs.size()];

        for (int i = 0; i < inputs.size(); i++)
            cursors[i] = resultSet(inputs.get(i), inputTypes.get(i));

        Row args = argumentsFactory.create((Object[])cursors);
        Iterable<Row> rows = new TableFunctionScan<>(rowType(), () -> function.apply(args), resultFactory);

        result = rows.iterator();
        flush();
    }

    /** */
    private CachedRowSet resultSet(List<Row> rows, RelDataType rowType) throws SQLException {
        CachedRowSet resultSet = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl meta = new RowSetMetaDataImpl();
        List<RelDataType> fieldTypes = new ArrayList<>(rowType.getFieldCount());

        meta.setColumnCount(rowType.getFieldCount());

        for (int i = 0; i < rowType.getFieldCount(); i++) {
            fieldTypes.add(rowType.getFieldList().get(i).getType());
            meta.setColumnName(i + 1, rowType.getFieldList().get(i).getName());
            meta.setColumnType(i + 1, fieldTypes.get(i).getSqlTypeName().getJdbcOrdinal());
        }

        resultSet.setMetaData(meta);

        RowHandler<Row> hnd = context().rowHandler();

        for (int rowIdx = rows.size() - 1; rowIdx >= 0; rowIdx--) {
            resultSet.moveToInsertRow();

            for (int colIdx = 0; colIdx < fieldTypes.size(); colIdx++)
                resultSet.updateObject(colIdx + 1, hnd.get(colIdx, rows.get(rowIdx)));

            resultSet.insertRow();
        }

        resultSet.moveToCurrentRow();
        resultSet.beforeFirst();

        return resultSet;
    }

    /** */
    private void flush() throws Exception {
        if (isClosed())
            return;

        int processed = 0;

        inLoop = true;
        try {
            while (requested > 0 && result.hasNext()) {
                checkState();

                requested--;
                downstream().push(result.next());

                if (++processed == IN_BUFFER_SIZE && requested > 0) {
                    context().execute(this::flush, this::onError);

                    return;
                }
            }
        }
        finally {
            inLoop = false;
        }

        if (requested > 0) {
            requested = 0;
            downstream().end();
        }
    }

    /** {@inheritDoc} */
    @Override protected void rewindInternal() {
        requested = 0;
        result = null;

        for (int i = 0; i < inputs.size(); i++) {
            inputs.get(i).clear();
            waiting[i] = 0;
        }
    }
}
