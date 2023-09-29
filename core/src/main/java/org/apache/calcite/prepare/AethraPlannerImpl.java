/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This is an AethraDB specific planner implementation, which aims to provide functionality to
 * parse and validate a query with substantially less overhead than the default PlannerImpl.
 * We reduce overhead by preventing the instantiation of (unused) static objects as much as possible.
 */
package org.apache.calcite.prepare;

import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCostFactory;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.AethraSqlToRelConverter;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.SqlRexConvertletTable;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.ValidationException;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Reader;

import static java.util.Objects.requireNonNull;

/** Implementation of {@link Planner}. */
public class AethraPlannerImpl {
  private final SqlOperatorTable operatorTable;
  private final ImmutableList<Program> programs;
  private final @Nullable RelOptCostFactory costFactory;
  private final Context context;
  private final CalciteConnectionConfig connectionConfig;

  /** Holds the trait definitions to be registered with planner. May be null. */
  private final @Nullable ImmutableList<RelTraitDef> traitDefs;

  private final SqlParser.Config parserConfig;
  private final SqlValidator.Config sqlValidatorConfig;
  private final SqlToRelConverter.Config sqlToRelConverterConfig;
  private final SqlRexConvertletTable convertletTable;

  // set in STATE_2_READY
  private @Nullable SchemaPlus defaultSchema;
  private @Nullable JavaTypeFactory typeFactory;
  private @Nullable RelOptPlanner planner;
  private @Nullable RexExecutor executor;

  // set in STATE_4_VALIDATE
  private @Nullable SqlValidator validator;
  private @Nullable SqlNode validatedSqlNode;

  public AethraPlannerImpl(FrameworkConfig config, JavaTypeFactory typeFactory) {
    this.costFactory = config.getCostFactory();
    this.defaultSchema = config.getDefaultSchema();
    this.operatorTable = config.getOperatorTable();
    this.programs = config.getPrograms();
    this.parserConfig = config.getParserConfig();
    this.sqlValidatorConfig = config.getSqlValidatorConfig();
    this.sqlToRelConverterConfig = config.getSqlToRelConverterConfig();
    this.traitDefs = config.getTraitDefs();
    this.convertletTable = config.getConvertletTable();
    this.executor = config.getExecutor();
    this.context = config.getContext();
    this.connectionConfig = connConfig(context, parserConfig);
    this.typeFactory = typeFactory;


    this.planner = new VolcanoPlanner(costFactory, context);
  }

  /** Gets a user-defined config and appends default connection values. */
  private static CalciteConnectionConfig connConfig(Context context,
      SqlParser.Config parserConfig) {
    CalciteConnectionConfigImpl config =
        context.maybeUnwrap(CalciteConnectionConfigImpl.class)
            .orElse(CalciteConnectionConfig.DEFAULT);
    if (!config.isSet(CalciteConnectionProperty.CASE_SENSITIVE)) {
      config =
          config.set(CalciteConnectionProperty.CASE_SENSITIVE,
              String.valueOf(parserConfig.caseSensitive()));
    }
    if (!config.isSet(CalciteConnectionProperty.CONFORMANCE)) {
      config =
          config.set(CalciteConnectionProperty.CONFORMANCE,
              String.valueOf(parserConfig.conformance()));
    }
    return config;
  }

  public SqlNode parse(final Reader reader) throws SqlParseException {
    SqlParser parser = SqlParser.create(reader, parserConfig);
    return parser.parseStmt();
  }

  @EnsuresNonNull("validator")
  public SqlNode validate(SqlNode sqlNode) throws ValidationException {
    this.validator = createSqlValidator(createCatalogReader());
    try {
      validatedSqlNode = validator.validate(sqlNode);
    } catch (RuntimeException e) {
      throw new ValidationException(e);
    }
    return validatedSqlNode;
  }

  public RelRoot rel(SqlNode sql) {
    SqlNode validatedSqlNode = requireNonNull(this.validatedSqlNode, "validatedSqlNode is null. Need to call #validate() first");
    final RexBuilder rexBuilder = createRexBuilder();
    final RelOptCluster cluster = RelOptCluster.create(requireNonNull(planner, "planner"), rexBuilder);
    final SqlToRelConverter.Config config = sqlToRelConverterConfig.withTrimUnusedFields(false);
    final AethraSqlToRelConverter sqlToRelConverter = new AethraSqlToRelConverter(validator, createCatalogReader(), cluster, convertletTable, config);
    RelRoot root = sqlToRelConverter.convertQuery(validatedSqlNode, true);
    root = root.withRel(sqlToRelConverter.flattenTypes(root.rel, true));
    final RelBuilder relBuilder = config.getRelBuilderFactory().create(cluster, null);
    root = root.withRel(RelDecorrelator.decorrelateQuery(root.rel, relBuilder));
    return root;
  }

  // CalciteCatalogReader is stateless; no need to store one
  private CalciteCatalogReader createCatalogReader() {
    SchemaPlus defaultSchema = requireNonNull(this.defaultSchema, "defaultSchema");
    final SchemaPlus rootSchema = rootSchema(defaultSchema);

    return new CalciteCatalogReader(
        CalciteSchema.from(rootSchema),
        CalciteSchema.from(defaultSchema).path(null),
        getTypeFactory(), connectionConfig);
  }

  private SqlValidator createSqlValidator(CalciteCatalogReader catalogReader) {
    final SqlOperatorTable opTab =
        SqlOperatorTables.chain(operatorTable, catalogReader);
    return new CalciteSqlValidator(opTab,
        catalogReader,
        getTypeFactory(),
        sqlValidatorConfig
            .withDefaultNullCollation(connectionConfig.defaultNullCollation())
            .withLenientOperatorLookup(connectionConfig.lenientOperatorLookup())
            .withConformance(connectionConfig.conformance())
            .withIdentifierExpansion(true));
  }

  private static SchemaPlus rootSchema(SchemaPlus schema) {
    for (;;) {
      SchemaPlus parentSchema = schema.getParentSchema();
      if (parentSchema == null) {
        return schema;
      }
      schema = parentSchema;
    }
  }

  // RexBuilder is stateless; no need to store one
  private RexBuilder createRexBuilder() {
    return new RexBuilder(getTypeFactory());
  }

  public JavaTypeFactory getTypeFactory() {
    return requireNonNull(typeFactory, "typeFactory");
  }
}
