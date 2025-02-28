/*
 * Copyright 2004-2019 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command.dml;

import org.h2.api.ErrorCode;
import org.h2.api.Trigger;
import org.h2.cdc.CDKeeper;
import org.h2.cdc.ChangeDataObject;
import org.h2.command.Command;
import org.h2.command.CommandInterface;
import org.h2.engine.Right;
import org.h2.engine.Session;
import org.h2.engine.UndoLogRecord;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionColumn;
import org.h2.expression.Parameter;
import org.h2.expression.ValueExpression;
import org.h2.expression.condition.Comparison;
import org.h2.expression.condition.ConditionAndOr;
import org.h2.index.Index;
import org.h2.message.DbException;
import org.h2.mvstore.db.MVPrimaryIndex;
import org.h2.pagestore.db.PageDataIndex;
import org.h2.result.ResultInterface;
import org.h2.result.ResultTarget;
import org.h2.result.Row;
import org.h2.table.Column;
import org.h2.table.DataChangeDeltaTable.ResultOption;
import org.h2.table.Table;
import org.h2.table.TableFilter;
import org.h2.value.Value;
import org.h2.value.ValueNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This class represents the statement
 * INSERT
 */
public class Insert extends CommandWithValues implements ResultTarget, DataChangeStatement {

    private static int a = 0;
    private Table table;
    private Column[] columns;
    private Query query;
    private boolean sortedInsertMode;
    private int rowNumber;
    private boolean insertFromSelect;
    /**
     * This table filter is for MERGE..USING support - not used in stand-alone DML
     */
    private TableFilter sourceTableFilter;
    /**
     * For MySQL-style INSERT ... ON DUPLICATE KEY UPDATE ....
     */
    private HashMap<Column, Expression> duplicateKeyAssignmentMap;
    /**
     * For MySQL-style INSERT IGNORE and PostgreSQL-style ON CONFLICT DO
     * NOTHING.
     */
    private boolean ignore;
    private ResultTarget deltaChangeCollector;
    private ResultOption deltaChangeCollectionMode;

    public Insert(Session session) {
        super(session);
    }

    @Override
    public void setCommand(Command command) {
        super.setCommand(command);
        if (query != null) {
            query.setCommand(command);
        }
    }

    @Override
    public Table getTable() {
        return table;
    }

    public void setTable(Table table) {
        this.table = table;
    }

    public void setColumns(Column[] columns) {
        this.columns = columns;
    }

    /**
     * Sets MySQL-style INSERT IGNORE mode or PostgreSQL-style ON CONFLICT
     * DO NOTHING.
     *
     * @param ignore ignore duplicates
     */
    public void setIgnore(boolean ignore) {
        this.ignore = ignore;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    /**
     * Keep a collection of the columns to pass to update if a duplicate key
     * happens, for MySQL-style INSERT ... ON DUPLICATE KEY UPDATE ....
     *
     * @param column     the column
     * @param expression the expression
     */
    public void addAssignmentForDuplicate(Column column, Expression expression) {
        if (duplicateKeyAssignmentMap == null) {
            duplicateKeyAssignmentMap = new HashMap<>();
        }
        if (duplicateKeyAssignmentMap.put(column, expression) != null) {
            throw DbException.get(ErrorCode.DUPLICATE_COLUMN_NAME_1, column.getName());
        }
    }

    @Override
    public void setDeltaChangeCollector(ResultTarget deltaChangeCollector, ResultOption deltaChangeCollectionMode) {
        this.deltaChangeCollector = deltaChangeCollector;
        this.deltaChangeCollectionMode = deltaChangeCollectionMode;
    }

    @Override
    public int update() {
        Index index = null;
        if (sortedInsertMode) {
            if (!session.getDatabase().isMVStore()) {
                /*
                 * Take exclusive lock, otherwise two different inserts running at
                 * the same time, the second might accidentally get
                 * sorted-insert-mode.
                 */
                table.lock(session, /* exclusive */true, /* forceLockEvenInMvcc */true);
            }
            index = table.getScanIndex(session);
            index.setSortedInsertMode(true);
        }
        try {
            return insertRows();
        } finally {
            if (index != null) {
                index.setSortedInsertMode(false);
            }
        }
    }

    private int insertRows() {
        ChangeDataObject cdObj = new ChangeDataObject("INSERT");
        Map afterData = new HashMap<String, String>();
        CDKeeper.setLastchange("Insert cdc ".concat(Integer.toString(a)));
        a = a + 1;

        session.getUser().checkRight(table, Right.INSERT);
        setCurrentRowNumber(0);
        table.fire(session, Trigger.INSERT, true);
        rowNumber = 0;
        int listSize = valuesExpressionList.size();
        if (listSize > 0) {
            int columnLen = columns.length;
            for (int x = 0; x < listSize; x++) {
                Row newRow = table.getTemplateRow();
                Expression[] expr = valuesExpressionList.get(x);
                setCurrentRowNumber(x + 1);
                for (int i = 0; i < columnLen; i++) {
                    Column c = columns[i];
                    String colName = c.getName();
                    int index = c.getColumnId();
                    Expression e = expr[i];
                    if (e != null) {
                        // e can be null (DEFAULT)
                        e = e.optimize(session);
                        try {
                            Value v = e.getValue(session);
                            String strValue = v.getString();
                            afterData.put(colName, strValue);
                            newRow.setValue(index, v);
                        } catch (DbException ex) {
                            throw setRow(ex, x, getSimpleSQL(expr));
                        }
                    }
                }
                rowNumber++;
                table.validateConvertUpdateSequence(session, newRow);
                cdObj.setAfter(afterData);
                CDKeeper.appendChangeData(cdObj);
                if (deltaChangeCollectionMode == ResultOption.NEW) {
                    deltaChangeCollector.addRow(newRow.getValueList().clone());
                }
                boolean done = table.fireBeforeRow(session, null, newRow);
                if (!done) {
                    table.lock(session, true, false);
                    try {
                        table.addRow(session, newRow);
                    } catch (DbException de) {
                        if (handleOnDuplicate(de, null)) {
                            // MySQL returns 2 for updated row
                            // TODO: detect no-op change
                            rowNumber++;
                        } else {
                            // INSERT IGNORE case
                            rowNumber--;
                        }
                        continue;
                    }
                    if (deltaChangeCollectionMode == ResultOption.FINAL) {
                        deltaChangeCollector.addRow(newRow.getValueList());
                    }
                    session.log(table, UndoLogRecord.INSERT, newRow);
                    table.fireAfterRow(session, null, newRow, false);
                }
            }
        } else {
            table.lock(session, true, false);
            if (insertFromSelect) {
                query.query(0, this);
            } else {
                ResultInterface rows = query.query(0);
                while (rows.next()) {
                    Value[] r = rows.currentRow();
                    try {
                        addRow(r);
                    } catch (DbException de) {
                        if (handleOnDuplicate(de, r)) {
                            // MySQL returns 2 for updated row
                            // TODO: detect no-op change
                            rowNumber++;
                        } else {
                            // INSERT IGNORE case
                            rowNumber--;
                        }
                    }
                }
                rows.close();
            }
        }
        table.fire(session, Trigger.INSERT, false);
        return rowNumber;
    }

    @Override
    public void addRow(Value... values) {
        Row newRow = table.getTemplateRow();
        setCurrentRowNumber(++rowNumber);
        for (int j = 0, len = columns.length; j < len; j++) {
            newRow.setValue(columns[j].getColumnId(), values[j]);
        }
        table.validateConvertUpdateSequence(session, newRow);
        if (deltaChangeCollectionMode == ResultOption.NEW) {
            deltaChangeCollector.addRow(newRow.getValueList().clone());
        }
        boolean done = table.fireBeforeRow(session, null, newRow);
        if (!done) {
            table.addRow(session, newRow);
            if (deltaChangeCollectionMode == ResultOption.FINAL) {
                deltaChangeCollector.addRow(newRow.getValueList());
            }
            session.log(table, UndoLogRecord.INSERT, newRow);
            table.fireAfterRow(session, null, newRow, false);
        }
    }

    @Override
    public int getRowCount() {
        return rowNumber;
    }

    @Override
    public void limitsWereApplied() {
        // Nothing to do
    }

    @Override
    public String getPlanSQL(boolean alwaysQuote) {
        StringBuilder builder = new StringBuilder("INSERT INTO ");
        table.getSQL(builder, alwaysQuote).append('(');
        Column.writeColumns(builder, columns, alwaysQuote);
        builder.append(")\n");
        if (insertFromSelect) {
            builder.append("DIRECT ");
        }
        if (sortedInsertMode) {
            builder.append("SORTED ");
        }
        if (!valuesExpressionList.isEmpty()) {
            builder.append("VALUES ");
            int row = 0;
            if (valuesExpressionList.size() > 1) {
                builder.append('\n');
            }
            for (Expression[] expr : valuesExpressionList) {
                if (row++ > 0) {
                    builder.append(",\n");
                }
                builder.append('(');
                Expression.writeExpressions(builder, expr, alwaysQuote);
                builder.append(')');
            }
        } else {
            builder.append(query.getPlanSQL(alwaysQuote));
        }
        return builder.toString();
    }

    @Override
    public void prepare() {
        if (columns == null) {
            if (!valuesExpressionList.isEmpty() && valuesExpressionList.get(0).length == 0) {
                // special case where table is used as a sequence
                columns = new Column[0];
            } else {
                columns = table.getColumns();
            }
        }
        if (!valuesExpressionList.isEmpty()) {
            for (Expression[] expr : valuesExpressionList) {
                if (expr.length != columns.length) {
                    throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
                }
                for (int i = 0, len = expr.length; i < len; i++) {
                    Expression e = expr[i];
                    if (e != null) {
                        if (sourceTableFilter != null) {
                            e.mapColumns(sourceTableFilter, 0, Expression.MAP_INITIAL);
                        }
                        e = e.optimize(session);
                        if (e instanceof Parameter) {
                            Parameter p = (Parameter) e;
                            p.setColumn(columns[i]);
                        }
                        expr[i] = e;
                    }
                }
            }
        } else {
            query.prepare();
            if (query.getColumnCount() != columns.length) {
                throw DbException.get(ErrorCode.COLUMN_COUNT_DOES_NOT_MATCH);
            }
        }
    }

    @Override
    public boolean isTransactional() {
        return true;
    }

    @Override
    public ResultInterface queryMeta() {
        return null;
    }

    public void setSortedInsertMode(boolean sortedInsertMode) {
        this.sortedInsertMode = sortedInsertMode;
    }

    @Override
    public int getType() {
        return CommandInterface.INSERT;
    }

    @Override
    public String getStatementName() {
        return "INSERT";
    }

    public void setInsertFromSelect(boolean value) {
        this.insertFromSelect = value;
    }

    @Override
    public boolean isCacheable() {
        return duplicateKeyAssignmentMap == null;
    }

    /**
     * @param de         duplicate key exception
     * @param currentRow current row values (optional)
     * @return {@code true} if row was updated, {@code false} if row was ignored
     */
    private boolean handleOnDuplicate(DbException de, Value[] currentRow) {
        if (de.getErrorCode() != ErrorCode.DUPLICATE_KEY_1) {
            throw de;
        }
        if (duplicateKeyAssignmentMap == null) {
            if (ignore) {
                return false;
            }
            throw de;
        }

        int columnCount = columns.length;
        ArrayList<String> variableNames = new ArrayList<>(columnCount);
        Expression[] row = (currentRow == null) ? valuesExpressionList.get((int) getCurrentRowNumber() - 1)
                : new Expression[columnCount];
        for (int i = 0; i < columnCount; i++) {
            StringBuilder builder = table.getSQL(new StringBuilder(), true).append('.');
            String key = columns[i].getSQL(builder, true).toString();
            variableNames.add(key);
            Value value;
            if (currentRow != null) {
                value = currentRow[i];
                row[i] = ValueExpression.get(value);
            } else {
                value = row[i].getValue(session);
            }
            session.setVariable(key, value);
        }

        StringBuilder builder = new StringBuilder("UPDATE ");
        table.getSQL(builder, true).append(" SET ");
        boolean f = false;
        for (Entry<Column, Expression> entry : duplicateKeyAssignmentMap.entrySet()) {
            if (f) {
                builder.append(", ");
            }
            f = true;
            entry.getKey().getSQL(builder, true).append('=');
            entry.getValue().getSQL(builder, true);
        }
        builder.append(" WHERE ");
        Index foundIndex = (Index) de.getSource();
        if (foundIndex == null) {
            throw DbException.getUnsupportedException(
                    "Unable to apply ON DUPLICATE KEY UPDATE, no index found!");
        }
        prepareUpdateCondition(foundIndex, row).getSQL(builder, true);
        String sql = builder.toString();
        Update command = (Update) session.prepare(sql);
        command.setUpdateToCurrentValuesReturnsZero(true);
        for (Parameter param : command.getParameters()) {
            Parameter insertParam = parameters.get(param.getIndex());
            param.setValue(insertParam.getValue(session));
        }
        boolean result = command.update() > 0;
        for (String variableName : variableNames) {
            session.setVariable(variableName, ValueNull.INSTANCE);
        }
        return result;
    }

    private Expression prepareUpdateCondition(Index foundIndex, Expression[] row) {
        // MVPrimaryIndex is playing fast and loose with it's implementation of
        // the Index interface.
        // It returns all of the columns in the table when we call
        // getIndexColumns() or getColumns().
        // Don't have time right now to fix that, so just special-case it.
        // PageDataIndex has the same problem.
        final Column[] indexedColumns;
        if (foundIndex instanceof MVPrimaryIndex) {
            MVPrimaryIndex foundMV = (MVPrimaryIndex) foundIndex;
            indexedColumns = new Column[]{foundMV.getIndexColumns()[foundMV
                    .getMainIndexColumn()].column};
        } else if (foundIndex instanceof PageDataIndex) {
            PageDataIndex foundPD = (PageDataIndex) foundIndex;
            int mainIndexColumn = foundPD.getMainIndexColumn();
            indexedColumns = mainIndexColumn >= 0
                    ? new Column[]{foundPD.getIndexColumns()[mainIndexColumn].column}
                    : foundIndex.getColumns();
        } else {
            indexedColumns = foundIndex.getColumns();
        }

        Expression condition = null;
        for (Column column : indexedColumns) {
            ExpressionColumn expr = new ExpressionColumn(session.getDatabase(),
                    table.getSchema().getName(), table.getName(),
                    column.getName(), false);
            for (int i = 0; i < columns.length; i++) {
                if (expr.getColumnName().equals(columns[i].getName())) {
                    if (condition == null) {
                        condition = new Comparison(session, Comparison.EQUAL, expr, row[i]);
                    } else {
                        condition = new ConditionAndOr(ConditionAndOr.AND, condition,
                                new Comparison(session, Comparison.EQUAL, expr, row[i]));
                    }
                    break;
                }
            }
        }
        return condition;
    }

    public void setSourceTableFilter(TableFilter sourceTableFilter) {
        this.sourceTableFilter = sourceTableFilter;
    }

}
