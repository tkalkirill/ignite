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

package org.apache.ignite.internal.processors.query.calcite.integration;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.apache.calcite.adapter.enumerable.NullPolicy;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.ReflectiveSqlOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.calcite.CalciteQueryEngineConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.SqlConfiguration;
import org.apache.ignite.indexing.IndexingQueryEngineConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgnitionEx;
import org.apache.ignite.internal.processors.query.IgniteSQLException;
import org.apache.ignite.internal.processors.query.QueryUtils;
import org.apache.ignite.internal.processors.query.calcite.CalciteQueryProcessor;
import org.apache.ignite.internal.processors.query.calcite.QueryChecker;
import org.apache.ignite.internal.processors.query.calcite.exec.ExecutionContext;
import org.apache.ignite.internal.processors.query.calcite.exec.exp.RexImpTable;
import org.apache.ignite.internal.processors.query.calcite.prepare.IgniteSqlNodeRewriter;
import org.apache.ignite.internal.processors.query.calcite.prepare.IgniteSqlValidator;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.plugin.AbstractTestPluginProvider;
import org.apache.ignite.plugin.PluginContext;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

public class RowIdPseudoColumnTest extends AbstractBasicIntegrationTest {
    @Override
    protected int nodeCount() {
        return 1;
    }

    @Override
    protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        SqlConfiguration sqlCfg = new SqlConfiguration().setQueryEnginesConfiguration(
            new CalciteQueryEngineConfiguration().setDefault(true),
            new IndexingQueryEngineConfiguration()
        );

        return super.getConfiguration(igniteInstanceName)
            .setSqlConfiguration(sqlCfg)
            .setPluginProviders(new RowIdPseudoColumnPluginProvider());
    }

    @Test
    public void testSimplePrimaryKeySelectAll() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", 0, "foo0");

        assertQuery("select * from PUBLIC.PERSON")
            .columnNames("ID", "NAME")
            .returns(0, "foo0")
            .check();
    }

    @Test
    public void testSimplePrimaryKeySelectRowIdOnly() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", 0, "foo0");

        Object rowid = sql("select rowid from PUBLIC.PERSON where id = ?", 0).get(0).get(0);
        assertTrue(Objects.toString(rowid), rowid instanceof String);

        // Проверим что это base64.
        try {
            Base64.getDecoder().decode((String) rowid);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("Failed to decode rowid=" + rowid, e);
        }

        assertQuery("select rowid from PUBLIC.PERSON")
            .columnNames("ROWID")
            .returns(rowid)
            .check();

        assertQuery("select rowid as frog from PUBLIC.PERSON")
            .columnNames("FROG")
            .returns(rowid)
            .check();

        assertQuery("select p.rowid from PUBLIC.PERSON as p")
            .columnNames("ROWID")
            .returns(rowid)
            .check();

        assertQuery("select p.rowid as frog from PUBLIC.PERSON as p")
            .columnNames("FROG")
            .returns(rowid)
            .check();
    }

    @Test
    public void testSimplePrimaryKeySelectWithRowId() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", 0, "foo0");
        String rowid = (String) sql("select rowid from PUBLIC.PERSON where id = ?", 0).get(0).get(0);

        assertQuery("select id, name, rowid from PUBLIC.PERSON")
            .columnNames("ID", "NAME", "ROWID")
            .returns(0, "foo0", rowid)
            .check();
    }

    @Test
    public void testSimplePrimaryKeySelectWithRowIdInWhere() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        for (int i = 0; i < 10; i++) {
            sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", i, "foo" + i);
        }

        List<List<?>> selectRs = sql("select rowid, id from PUBLIC.PERSON order by id");
        String rowId4 = (String) selectRs.get(4).get(0);
        String rowId6 = (String) selectRs.get(6).get(0);
        String rowId7 = (String) selectRs.get(7).get(0);
        String rowId8 = (String) selectRs.get(8).get(0);

        Integer id4 = (Integer) selectRs.get(4).get(1);

        assertQuery("select id, name, rowid from PUBLIC.PERSON where rowid = ?")
            .withParams(rowId7)
            .columnNames("ID", "NAME", "ROWID")
            .matches(QueryChecker.containsIndexScan("PUBLIC", "PERSON", "_key_PK"))
            .returns(7, "foo7", rowId7)
            .check();

        assertQuery("select p.id, p.name, p.rowid from PUBLIC.PERSON as p where ? = p.rowid")
            .withParams(rowId6)
            .columnNames("ID", "NAME", "ROWID")
            .matches(QueryChecker.containsIndexScan("PUBLIC", "PERSON", "_key_PK"))
            .returns(6, "foo6", rowId6)
            .check();

        assertQuery(String.format("select p.id, p.name, p.rowid from PUBLIC.PERSON as p where p.rowid = '%s'", rowId8))
            .columnNames("ID", "NAME", "ROWID")
            .matches(QueryChecker.containsIndexScan("PUBLIC", "PERSON", "_key_PK"))
            .returns(8, "foo8", rowId8)
            .check();

        assertQuery("select p.id, p.name, p.rowid from PUBLIC.PERSON as p where p.rowid = ? and p.id = ?")
            .withParams(rowId4, id4)
            .columnNames("ID", "NAME", "ROWID")
            .matches(QueryChecker.containsIndexScan("PUBLIC", "PERSON", "_key_PK"))
            .returns(4, "foo4", rowId4)
            .check();
    }

    @Test
    public void testSimplePrimaryKeySelectWithOrderByRowId() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        for (int i = 0; i < 10; i++) {
            sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", i, "foo" + i);
        }

        List<List<?>> selectRs = sql("select id, name, rowid from PUBLIC.PERSON");
        selectRs.sort((o1, o2) -> CharSequence.compare(((CharSequence) o1.get(2)), ((CharSequence) o2.get(2))));

        QueryChecker queryChecker = assertQuery("select id, name, rowid from PUBLIC.PERSON order by rowid")
            .columnNames("ID", "NAME", "ROWID");

        for (List<?> row : selectRs) {
            queryChecker.returns(row.toArray(Object[]::new));
        }

        queryChecker.check();
    }

    @Test
    public void testSimplePrimaryKeyWithJoin() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar, city_id int)");
        sql("create table PUBLIC.CITY(id int primary key, name varchar)");

        for (int i = 0; i < 2; i++) {
            sql("insert into PUBLIC.PERSON(id, name, city_id) values(?, ?, ?)", i, "foo" + i, i);
            sql("insert into PUBLIC.CITY(id, name) values(?, ?)", i, "city" + i);
        }

        List<List<?>> selectRs0 = sql("select rowid, id from PUBLIC.PERSON order by id");
        Object p_rowId0 = selectRs0.get(0).get(0);
        Object p_rowId1 = selectRs0.get(1).get(0);
        Object p_id0 = selectRs0.get(0).get(1);
        Object p_id1 = selectRs0.get(1).get(1);

        List<List<?>> selectRs1 = sql("select rowid, id from PUBLIC.CITY order by id");
        Object c_rowId0 = selectRs1.get(0).get(0);
        Object c_rowId1 = selectRs1.get(1).get(0);

        assertQuery("select p.id as p_id, p.name as p_name, c.name as c_name, p.rowid as p_rowid, c.rowid as c_rowid " +
            "from PUBLIC.PERSON p " +
            "join PUBLIC.CITY c on c.rowid = p.rowid"
        )
            .columnNames("P_ID", "P_NAME", "C_NAME", "P_ROWID", "C_ROWID")
            .returns(p_id0, "foo0", "city0", p_rowId0, c_rowId0)
            .returns(p_id1, "foo1", "city1", p_rowId1, c_rowId1)
            .check();
    }

    @Test
    public void testSimplePrimaryKeyWithRowIdInWhereWithIn() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        for (int i = 0; i < 10; i++) {
            sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", i, "foo" + i);
        }

        List<List<?>> selectRs = sql("select rowid, id from PUBLIC.PERSON order by id");
        String rowId4 = (String) selectRs.get(4).get(0);
        String rowId6 = (String) selectRs.get(6).get(0);

        RowIdPseudoColumnNodeRewriter.startLog = true;

        assertQuery("select id, name, rowid from PUBLIC.PERSON as p where p.rowid in (?, ?)")
            .withParams(rowId4, rowId6)
            .columnNames("ID", "NAME", "ROWID")
            .matches(QueryChecker.containsIndexScan("PUBLIC", "PERSON", "_key_PK"))
            .returns(4, "foo4", rowId4)
            .returns(6, "foo6", rowId6)
            .check();
    }

    @Test
    public void testCompositePrimaryKey() {
        sql("create table PUBLIC.PERSON(id int, name varchar, age int, primary key (id, name))");

        for (int i = 0; i < 10; i++) {
            sql("insert into PUBLIC.PERSON(id, name, age) values(?, ?, ?)", i, "foo" + i, 18 + i);
        }

        List<List<?>> selectRs = sql("select rowid, _key from PUBLIC.PERSON order by id");
        Object rowid = selectRs.get(6).get(0);

        assertTrue(Objects.toString(rowid), rowid instanceof String);

        // Проверим что это base64.
        try {
            Base64.getDecoder().decode((String) rowid);
        } catch (IllegalArgumentException e) {
            throw new AssertionError("Failed to decode rowid=" + rowid, e);
        }

        assertQuery("select id, name, age, rowid from PUBLIC.PERSON where rowid = ?")
            .withParams(rowid)
            .columnNames("ID", "NAME", "AGE", "ROWID")
            .matches(QueryChecker.containsIndexScan("PUBLIC", "PERSON", "_key_PK"))
            .returns(6, "foo6", 24, rowid)
            .check();
    }

    @Test
    public void testInsertWithRowId() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        assertThrows(
            "insert into PUBLIC.PERSON(id, name, rowid) values(?, ?, ?)",
            IgniteSQLException.class,
            "ROWID cannot be used in INSERT",
            0, "foo0", "invalid_value"
        );

        assertQuery("select id, name from PUBLIC.PERSON")
            .resultSize(0)
            .check();
    }

    @Test
    public void testUpdateWithRowId() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", 0, "foo0");

        String rowid = (String) sql("select rowid from PUBLIC.PERSON where id = ?", 0).get(0).get(0);

        assertThrows(
            "update PUBLIC.PERSON set name = ?, rowid = ? where id = ?",
            IgniteSQLException.class,
            "ROWID cannot be used in UPDATE",
            "fooNew", "invalid_value", 0
        );

        assertQuery("select id, name, rowid from PUBLIC.PERSON")
            .returns(0, "foo0", rowid)
            .check();

        sql("update PUBLIC.PERSON set name = ? where rowid = ?", "fooNew" , rowid);

        assertQuery("select id, name, rowid from PUBLIC.PERSON")
            .returns(0, "fooNew", rowid)
            .check();
    }

    @Test
    public void testDeleteWithRowId() {
        sql("create table PUBLIC.PERSON(id int primary key, name varchar)");

        sql("insert into PUBLIC.PERSON(id, name) values(?, ?)", 0, "foo0");

        String rowid = (String) sql("select rowid from PUBLIC.PERSON where id = ?", 0).get(0).get(0);

        sql("delete from PUBLIC.PERSON where rowid = ?", rowid);

        assertQuery("select id, name from PUBLIC.PERSON")
            .resultSize(0)
            .check();
    }

    public static @Nullable String toRowIdFromKey(ExecutionContext<?> ctx, @Nullable Object key) {
        if (key == null) {
            return null;
        }

        IgniteEx n = (IgniteEx) IgnitionEx.grid(ctx.localNodeId());

        byte[] bytes;

        try {
            bytes = U.marshal(n.context(), key);
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(String.format("Failed to marshal _key: [key=%s]", key), e);
        }

        return Base64.getEncoder().encodeToString(bytes);
    }

    public static @Nullable Object toKeyFromRowId(ExecutionContext<?> ctx, @Nullable String rowIdBase64) {
        if (rowIdBase64 == null) {
            return null;
        }

        IgniteEx n = (IgniteEx) IgnitionEx.grid(ctx.localNodeId());

        byte[] bytes = Base64.getDecoder().decode(rowIdBase64);

        try {
            return U.unmarshal(n.context(), bytes, null);
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(String.format("Failed to unmarshal rowId: [rowIdBase64=%s]", rowIdBase64), e);
        }
    }

    private static class RowIdPseudoColumnPluginProvider extends AbstractTestPluginProvider {
        @Override
        public String name() {
            return getClass().getSimpleName();
        }

        @Override
        public <T> @Nullable T createComponent(PluginContext ctx, Class<T> cls) {
            if (FrameworkConfig.class.equals(cls)) {
                return (T)Frameworks.newConfigBuilder(CalciteQueryProcessor.FRAMEWORK_CONFIG)
                    .operatorTable(SqlOperatorTables.chain(
                        new RowIdPseudoColumnOperatorTable().init(),
                        CalciteQueryProcessor.FRAMEWORK_CONFIG.getOperatorTable()
                    ))
                    .sqlValidatorConfig(
                        ((IgniteSqlValidator.Config)CalciteQueryProcessor.FRAMEWORK_CONFIG.getSqlValidatorConfig())
                            .withSqlNodeRewriter(FlexSoftIgniteSqlNodeRewriter.firstNotNull(new RowIdPseudoColumnNodeRewriter())))
                    .build();
            }

            return super.createComponent(ctx, cls);
        }

        @Override
        public void start(PluginContext ctx) throws IgniteCheckedException {
            RexImpTable.INSTANCE.define(
                RowIdPseudoColumnOperatorTable.TO_ROW_ID_FROM_KEY,
                RexImpTable.createRexCallImplementor((translator, call, translatedOperands) -> {
                    var key = Expressions.convert_(translatedOperands.get(0), Object.class);
                    var ectx = Expressions.convert_(translator.getRoot(), ExecutionContext.class);

                    return Expressions.call(RowIdPseudoColumnTest.class, "toRowIdFromKey", ectx, key);
                }, NullPolicy.ANY, false)
            );

            RexImpTable.INSTANCE.define(
                RowIdPseudoColumnOperatorTable.TO_KEY_FROM_ROW_ID,
                RexImpTable.createRexCallImplementor((translator, call, translatedOperands) -> {
                    var rowIdBase64 = Expressions.convert_(translatedOperands.get(0), String.class);
                    var ectx = Expressions.convert_(translator.getRoot(), ExecutionContext.class);

                    return Expressions.call(RowIdPseudoColumnTest.class, "toKeyFromRowId", ectx, rowIdBase64);
                }, NullPolicy.ANY, false)
            );
        }
    }

    public static class RowIdPseudoColumnOperatorTable extends ReflectiveSqlOperatorTable {
        public static final SqlFunction TO_ROW_ID_FROM_KEY = new SqlFunction(
            "TO_ROW_ID_FROM_KEY",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.VARCHAR,
            null,
            OperandTypes.ANY,
            SqlFunctionCategory.USER_DEFINED_FUNCTION
        );

        public static final SqlFunction TO_KEY_FROM_ROW_ID = new SqlFunction(
            "TO_KEY_FROM_ROW_ID",
            SqlKind.OTHER_FUNCTION,
            ReturnTypes.explicit(SqlTypeName.ANY),
            null,
            OperandTypes.ANY,
            SqlFunctionCategory.USER_DEFINED_FUNCTION
        ) {
            @Override
            public boolean isDeterministic() {
                return false; // важно: чтобы Calcite не константно сворачивал в Java-объект
            }
        };
    }

    /** Интерфейс для перезаписи SQL узлов во время валидации запроса. */
    @FunctionalInterface
    public interface FlexSoftIgniteSqlNodeRewriter {
        /**
         * Возвращает {@code null} если менять ничего не надо.
         *
         * @throws IgniteSQLException Если что-то пошло не так или нужно бросить исключение по валидации узла.
         */
        @Nullable SqlNode rewrite(SqlValidator validator, SqlNode node);

        /** Вернет составной переписывателся SQL запросов на валидации. Будет возвращаться первый не {@code null} новый SQL узел. */
        static IgniteSqlNodeRewriter firstNotNull(FlexSoftIgniteSqlNodeRewriter... rewriters) {
            return (validator, node) -> {
                for (int i = 0; i < rewriters.length; i++) {
                    SqlNode rewrited = rewriters[i].rewrite(validator, node);

                    if (rewrited != null) {
                        return rewrited;
                    }
                }

                return node;
            };
        }
    }

    private static class RowIdPseudoColumnNodeRewriter implements FlexSoftIgniteSqlNodeRewriter {
        public static final String COLUMN_NAME = "ROWID";

        public static volatile boolean startLog = false;

        @Override
        public @Nullable SqlNode rewrite(SqlValidator validator, SqlNode node) {
            if (startLog)
                log.info(">>>>> sqlNode: " + node);

            if (isRowIdIdent(node)) {
                SqlIdentifier rowId = (SqlIdentifier)node;
                SqlIdentifier keyId = rowId.setName(rowId.names.size() - 1, QueryUtils.KEY_FIELD_NAME);

                return RowIdPseudoColumnOperatorTable.TO_ROW_ID_FROM_KEY.createCall(node.getParserPosition(), keyId);
            } else if (node instanceof SqlCall && ((SqlCall)node).getOperator() == SqlStdOperatorTable.EQUALS) {
                SqlCall call = (SqlCall)node;

                if (isRowIdCall(call.operand(0))) {
                    return rewriteRowIdEqualsParam(call.getParserPosition(), call.operand(1), call.operand(0));
                }

                if (isRowIdCall(call.operand(1))) {
                    return rewriteRowIdEqualsParam(call.getParserPosition(), call.operand(0), call.operand(1));
                }
            } else if (node instanceof SqlSelect) {
                SqlSelect select = (SqlSelect)node;

                List<SqlNode> selectListItems = select.getSelectList().getList();
                for (int i = 0; i < selectListItems.size(); i++) {
                    SqlNode selectListItem = selectListItems.get(i);

                    if (isRowIdCall(selectListItem)) {
                        SqlCall toRowIdAsRowId = SqlStdOperatorTable.AS.createCall(
                            select.getParserPosition(),
                            selectListItem,
                            new SqlIdentifier(COLUMN_NAME, select.getParserPosition())
                        );

                        selectListItems.set(i, toRowIdAsRowId);
                    }
                }

                return select;
            } else if (node instanceof SqlUpdate) {
                checkTargetColumnList(((SqlUpdate) node).getTargetColumnList(), "UPDATE");
            } else if (node instanceof SqlInsert) {
                checkTargetColumnList(((SqlInsert) node).getTargetColumnList(), "INSERT");
            }

            return null;
        }

        private static void checkTargetColumnList(SqlNodeList list, String statement) {
            if (!F.isEmpty(list)) {
                for (SqlNode target : list) {
                    if (isRowIdCall(target)) {
                        throw new IgniteSQLException(String.format("%s cannot be used in %s", COLUMN_NAME, statement));
                    }
                }
            }
        }

        private static boolean isRowIdIdent(SqlNode node) {
            if (!(node instanceof SqlIdentifier)) {
                return false;
            }

            SqlIdentifier id = (SqlIdentifier)node;
            return COLUMN_NAME.equalsIgnoreCase(id.names.get(id.names.size() - 1));
        }

        private static boolean isRowIdCall(SqlNode node) {
            if (!(node instanceof SqlCall)) {
                return false;
            }

            return ((SqlCall)node).getOperator() == RowIdPseudoColumnOperatorTable.TO_ROW_ID_FROM_KEY;
        }

        private static SqlNode rewriteRowIdEqualsParam(SqlParserPos pos, SqlNode op1, SqlCall rowIdCall) {
            return SqlStdOperatorTable.EQUALS.createCall(pos, rowIdCall.operand(0),
                RowIdPseudoColumnOperatorTable.TO_KEY_FROM_ROW_ID.createCall(pos, op1));
        }
    }
}
