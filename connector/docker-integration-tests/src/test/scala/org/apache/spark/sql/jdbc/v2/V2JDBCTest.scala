/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.jdbc.v2

import org.apache.logging.log4j.Level

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{AnalysisException, DataFrame}
import org.apache.spark.sql.catalyst.analysis.{IndexAlreadyExistsException, NoSuchIndexException}
import org.apache.spark.sql.connector.DataSourcePushdownTestUtils
import org.apache.spark.sql.connector.catalog.{Catalogs, Identifier, TableCatalog}
import org.apache.spark.sql.connector.catalog.index.SupportsIndex
import org.apache.spark.sql.connector.expressions.NullOrdering
import org.apache.spark.sql.execution.datasources.jdbc.JDBCRDD
import org.apache.spark.sql.jdbc.DockerIntegrationFunSuite
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.tags.DockerTest

@DockerTest
private[v2] trait V2JDBCTest
  extends DataSourcePushdownTestUtils
  with DockerIntegrationFunSuite
  with SharedSparkSession {
  import testImplicits._

  val catalogName: String

  val namespaceOpt: Option[String] = None

  private def catalogAndNamespace =
    namespaceOpt.map(namespace => s"$catalogName.$namespace").getOrElse(catalogName)

  // dialect specific update column type test
  def testUpdateColumnType(tbl: String): Unit

  def notSupportsTableComment: Boolean = false

  def defaultMetadata(
      dataType: DataType = StringType,
      jdbcClientType: String = "STRING"): Metadata = new MetadataBuilder()
    .putLong("scale", 0)
    .putBoolean("isTimestampNTZ", false)
    .putBoolean("isSigned", dataType.isInstanceOf[NumericType])
    .putString("jdbcClientType", jdbcClientType)
    .build()

  /**
   * Returns a copy of the given [[StructType]] with the specified metadata key removed
   * from all of its fields.
   */
  def removeMetadataFromAllFields(structType: StructType, metadataKey: String): StructType = {
    val updatedFields = structType.fields.map { field =>
      val oldMetadata = field.metadata
      val newMetadataBuilder = new MetadataBuilder()
        .withMetadata(oldMetadata)
        .remove(metadataKey)
      field.copy(metadata = newMetadataBuilder.build())
    }
    StructType(updatedFields)
  }

  def testUpdateColumnNullability(tbl: String): Unit = {
    sql(s"CREATE TABLE $catalogName.alt_table (ID STRING NOT NULL)")
    var t = spark.table(s"$catalogName.alt_table")
    // nullable is true in the expectedSchema because Spark always sets nullable to true
    // regardless of the JDBC metadata https://github.com/apache/spark/pull/18445
    var expectedSchema = new StructType().add("ID", StringType, true, defaultMetadata())
    // If function is not overriden we don't want to compare external engine types
    var expectedSchemaWithoutJdbcClientType =
      removeMetadataFromAllFields(expectedSchema, "jdbcClientType")
    var schemaWithoutJdbcClientType =
      removeMetadataFromAllFields(t.schema, "jdbcClientType")
    assert(schemaWithoutJdbcClientType === expectedSchemaWithoutJdbcClientType)
    sql(s"ALTER TABLE $catalogName.alt_table ALTER COLUMN ID DROP NOT NULL")
    t = spark.table(s"$catalogName.alt_table")
    expectedSchema = new StructType().add("ID", StringType, true, defaultMetadata())

    // If function is not overriden we don't want to compare external engine types
    expectedSchemaWithoutJdbcClientType =
      removeMetadataFromAllFields(expectedSchema, "jdbcClientType")
    schemaWithoutJdbcClientType =
      removeMetadataFromAllFields(t.schema, "jdbcClientType")
    assert(schemaWithoutJdbcClientType === expectedSchemaWithoutJdbcClientType)
    // Update nullability of not existing column
    val sqlText = s"ALTER TABLE $catalogName.alt_table ALTER COLUMN bad_column DROP NOT NULL"
    checkError(
      exception = intercept[AnalysisException] {
        sql(sqlText)
      },
      condition = "UNRESOLVED_COLUMN.WITH_SUGGESTION",
      sqlState = "42703",
      parameters = Map(
        "objectName" -> "`bad_column`",
        "proposal" -> "`ID`"),
      context = ExpectedContext(fragment = sqlText, start = 0, stop = sqlText.length -1))
  }

  def testRenameColumn(tbl: String): Unit = {
    sql(s"ALTER TABLE $tbl RENAME COLUMN ID TO RENAMED")
    val t = spark.table(s"$tbl")
    val expectedSchema = new StructType().add("RENAMED", StringType, true, defaultMetadata())
      .add("ID1", StringType, true, defaultMetadata())
      .add("ID2", StringType, true, defaultMetadata())

    // If function is not overriden we don't want to compare external engine types
    val expectedSchemaWithoutJdbcClientType =
      removeMetadataFromAllFields(expectedSchema, "jdbcClientType")
    val schemaWithoutJdbcClientType =
      removeMetadataFromAllFields(t.schema, "jdbcClientType")
    assert(schemaWithoutJdbcClientType === expectedSchemaWithoutJdbcClientType)
  }

  def testCreateTableWithProperty(tbl: String): Unit = {}

  private def checkErrorFailedJDBC(
      e: AnalysisException,
      condition: String,
      tbl: String): Unit = {
    checkErrorMatchPVals(
      exception = e,
      condition = condition,
      parameters = Map(
        "url" -> "jdbc:.*",
        "tableName" -> s"`$tbl`")
    )
  }

  case class PartitioningInfo(
      numPartitions: String,
      lowerBound: String,
      upperBound: String,
      partitionColumn: String)
  val tableNameToPartinioningOptions: Map[String, PartitioningInfo] = Map(
    "employee" -> PartitioningInfo("4", "1", "8", "dept"),
    // new_table is used in "SPARK-37038: Test TABLESAMPLE" test
    "new_table" -> PartitioningInfo("4", "1", "20", "col1")
  )

  protected def getPartitioningInfo(testTableName: String): (String, Option[PartitioningInfo]) = {
    tableNameToPartinioningOptions.get(testTableName) match {
      case Some(partitionInfo) =>
        val partitioningInfoString = Seq(
          s"'numPartitions' '${partitionInfo.numPartitions}'",
          s"'lowerBound' '${partitionInfo.lowerBound}'",
          s"'upperBound' '${partitionInfo.upperBound}'",
          s"'partitionColumn' '${partitionInfo.partitionColumn}'"
        ).mkString(",")
        (s"WITH ($partitioningInfoString)", Some(partitionInfo))
      case _ => ("", None)
    }
  }

  private def getTableOptions(
      tableName: String,
      partitioningEnabled: Boolean): (String, Option[PartitioningInfo]) = {
    if (partitioningEnabled) {
      getPartitioningInfo(tableName)
    } else {
      ("", None)
    }
  }

  // This method is used to verify that the number of JDBCRDD partitions is
  // equal to numPartitions
  def multiplePartitionAdditionalCheck(
      df: DataFrame,
      partitioningInfo: Option[PartitioningInfo]): Unit = {
    def getJDBCRDD(rdd: RDD[_]): Option[JDBCRDD] = {
      rdd match {
        case jdbcRdd: JDBCRDD => Some(jdbcRdd)
        case _ if rdd.firstParent != null => getJDBCRDD(rdd.firstParent)
        case _ => None
      }
    }

    val jdbcRdd = getJDBCRDD(df.rdd)
    assert(jdbcRdd.isDefined && jdbcRdd.get.getNumPartitions ==
      partitioningInfo.get.numPartitions.toInt)
  }

  test("SPARK-33034: ALTER TABLE ... add new columns") {
    withTable(s"$catalogName.alt_table") {
      sql(s"CREATE TABLE $catalogName.alt_table (ID STRING)")
      var t = spark.table(s"$catalogName.alt_table")
      var expectedSchema = new StructType()
        .add("ID", StringType, true, defaultMetadata())
      var expectedSchemaWithoutJdbcClientType =
        removeMetadataFromAllFields(expectedSchema, "jdbcClientType")
      var schemaWithoutJdbcClientType =
        removeMetadataFromAllFields(t.schema, "jdbcClientType")
      assert(schemaWithoutJdbcClientType === expectedSchemaWithoutJdbcClientType)
      sql(s"ALTER TABLE $catalogName.alt_table ADD COLUMNS (C1 STRING, C2 STRING)")
      t = spark.table(s"$catalogName.alt_table")
      expectedSchema = expectedSchema
        .add("C1", StringType, true, defaultMetadata())
        .add("C2", StringType, true, defaultMetadata())
      expectedSchemaWithoutJdbcClientType =
        removeMetadataFromAllFields(expectedSchema, "jdbcClientType")
      schemaWithoutJdbcClientType =
        removeMetadataFromAllFields(t.schema, "jdbcClientType")
      assert(schemaWithoutJdbcClientType === expectedSchemaWithoutJdbcClientType)
      sql(s"ALTER TABLE $catalogName.alt_table ADD COLUMNS (C3 STRING)")
      t = spark.table(s"$catalogName.alt_table")
      expectedSchema = expectedSchema
        .add("C3", StringType, true, defaultMetadata())
      expectedSchemaWithoutJdbcClientType =
        removeMetadataFromAllFields(expectedSchema, "jdbcClientType")
      schemaWithoutJdbcClientType =
        removeMetadataFromAllFields(t.schema, "jdbcClientType")
      assert(schemaWithoutJdbcClientType === expectedSchemaWithoutJdbcClientType)
      // Add already existing column
      checkError(
        exception = intercept[AnalysisException] {
          sql(s"ALTER TABLE $catalogName.alt_table ADD COLUMNS (C3 DOUBLE)")
        },
        condition = "FIELD_ALREADY_EXISTS",
        parameters = Map(
          "op" -> "add",
          "fieldNames" -> "`C3`",
          "struct" -> """"STRUCT<ID: STRING, C1: STRING, C2: STRING, C3: STRING>""""),
        context = ExpectedContext(
          fragment = s"ALTER TABLE $catalogName.alt_table ADD COLUMNS (C3 DOUBLE)",
          start = 0,
          stop = 45 + catalogName.length)
      )
    }
    // Add a column to not existing table
    val e = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table ADD COLUMNS (C4 STRING)")
    }
    checkErrorTableNotFound(
      e,
      s"`$catalogName`.`not_existing_table`",
      ExpectedContext(
        s"$catalogName.not_existing_table", 12, 11 + s"$catalogName.not_existing_table".length))
  }

  test("SPARK-33034: ALTER TABLE ... drop column") {
    withTable(s"$catalogName.alt_table") {
      sql(s"CREATE TABLE $catalogName.alt_table (C1 INTEGER, C2 STRING, c3 INTEGER)")
      sql(s"ALTER TABLE $catalogName.alt_table DROP COLUMN C1")
      sql(s"ALTER TABLE $catalogName.alt_table DROP COLUMN c3")
      val t = spark.table(s"$catalogName.alt_table")
      val expectedSchema = new StructType()
        .add("C2", StringType, true, defaultMetadata())
      val expectedSchemaWithoutJdbcClientType =
        removeMetadataFromAllFields(expectedSchema, "jdbcClientType")
      val schemaWithoutJdbcClientType =
        removeMetadataFromAllFields(t.schema, "jdbcClientType")
      assert(schemaWithoutJdbcClientType === expectedSchemaWithoutJdbcClientType)
      // Drop not existing column
      val sqlText = s"ALTER TABLE $catalogName.alt_table DROP COLUMN bad_column"
      checkError(
        exception = intercept[AnalysisException] {
          sql(sqlText)
        },
        condition = "UNRESOLVED_COLUMN.WITH_SUGGESTION",
        sqlState = "42703",
        parameters = Map(
          "objectName" -> "`bad_column`",
          "proposal" -> "`C2`"),
        context = ExpectedContext(fragment = sqlText, start = 0, stop = sqlText.length -1))
    }
    // Drop a column from a not existing table
    val e = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table DROP COLUMN C1")
    }
    checkErrorTableNotFound(
      e,
      s"`$catalogName`.`not_existing_table`",
      ExpectedContext(
        s"$catalogName.not_existing_table", 12, 11 + s"$catalogName.not_existing_table".length))
  }

  test("SPARK-33034: ALTER TABLE ... update column type") {
    withTable(s"$catalogName.alt_table") {
      testUpdateColumnType(s"$catalogName.alt_table")
      // Update not existing column
      val sqlText = s"ALTER TABLE $catalogName.alt_table ALTER COLUMN bad_column TYPE DOUBLE"
      checkError(
        exception = intercept[AnalysisException] {
          sql(sqlText)
        },
        condition = "UNRESOLVED_COLUMN.WITH_SUGGESTION",
        sqlState = "42703",
        parameters = Map(
          "objectName" -> "`bad_column`",
          "proposal" -> "`ID`"),
        context = ExpectedContext(fragment = sqlText, start = 0, stop = sqlText.length -1))
    }
    // Update column type in not existing table
    val e = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table ALTER COLUMN id TYPE DOUBLE")
    }
    checkErrorTableNotFound(
      e,
      s"`$catalogName`.`not_existing_table`",
      ExpectedContext(
        s"$catalogName.not_existing_table", 12, 11 + s"$catalogName.not_existing_table".length))
  }

  test("SPARK-33034: ALTER TABLE ... rename column") {
    withTable(s"$catalogName.alt_table") {
      sql(s"CREATE TABLE $catalogName.alt_table (ID STRING NOT NULL," +
        s" ID1 STRING NOT NULL, ID2 STRING NOT NULL)")
      testRenameColumn(s"$catalogName.alt_table")
      // Rename to already existing column
      checkError(
        exception = intercept[AnalysisException] {
          sql(s"ALTER TABLE $catalogName.alt_table RENAME COLUMN ID1 TO ID2")
        },
        condition = "FIELD_ALREADY_EXISTS",
        parameters = Map(
          "op" -> "rename",
          "fieldNames" -> "`ID2`",
          "struct" -> """"STRUCT<RENAMED: STRING, ID1: STRING, ID2: STRING>""""),
        context = ExpectedContext(
          fragment = s"ALTER TABLE $catalogName.alt_table RENAME COLUMN ID1 TO ID2",
          start = 0,
          stop = 46 + catalogName.length)
      )
    }
    // Rename a column in a not existing table
    val e = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table RENAME COLUMN ID TO C")
    }
    checkErrorTableNotFound(
      e,
      s"`$catalogName`.`not_existing_table`",
      ExpectedContext(
        s"$catalogName.not_existing_table", 12, 11 + s"$catalogName.not_existing_table".length))
  }

  test("SPARK-33034: ALTER TABLE ... update column nullability") {
    withTable(s"$catalogName.alt_table") {
      testUpdateColumnNullability(s"$catalogName.alt_table")
    }
    // Update column nullability in not existing table
    val e = intercept[AnalysisException] {
      sql(s"ALTER TABLE $catalogName.not_existing_table ALTER COLUMN ID DROP NOT NULL")
    }
    checkErrorTableNotFound(
      e,
      s"`$catalogName`.`not_existing_table`",
      ExpectedContext(
        s"$catalogName.not_existing_table", 12, 11 + s"$catalogName.not_existing_table".length))
  }

  test("CREATE TABLE with table comment") {
    withTable(s"$catalogName.new_table") {
      val logAppender = new LogAppender("table comment")
      withLogAppender(logAppender) {
        sql(s"CREATE TABLE $catalogName.new_table(i INT) COMMENT 'this is a comment'")
      }
      val createCommentWarning = logAppender.loggingEvents
        .filter(_.getLevel == Level.WARN)
        .map(_.getMessage.getFormattedMessage)
        .exists(_.contains("Cannot create JDBC table comment"))
      assert(createCommentWarning === notSupportsTableComment)
    }
  }

  test("CREATE TABLE with table property") {
    withTable(s"$catalogName.new_table") {
      val e = intercept[AnalysisException] {
        sql(s"CREATE TABLE $catalogName.new_table (i INT) TBLPROPERTIES('a'='1')")
      }
      checkErrorFailedJDBC(e, "FAILED_JDBC.CREATE_TABLE", "new_table")
      testCreateTableWithProperty(s"$catalogName.new_table")
    }
  }

  def supportsIndex: Boolean = false

  def supportListIndexes: Boolean = false

  def indexOptions: String = ""

  test("SPARK-36895: Test INDEX Using SQL") {
    if (supportsIndex) {
      withTable(s"$catalogName.new_table") {
        sql(s"CREATE TABLE $catalogName.new_table(col1 INT, col2 INT, col3 INT," +
          " col4 INT, col5 INT)")
        val loaded = Catalogs.load(catalogName, conf)
        val jdbcTable = loaded.asInstanceOf[TableCatalog]
          .loadTable(Identifier.of(Array.empty[String], "new_table"))
          .asInstanceOf[SupportsIndex]
        assert(jdbcTable.indexExists("i1") == false)
        assert(jdbcTable.indexExists("i2") == false)

        val indexType = "DUMMY"
        val m = intercept[UnsupportedOperationException] {
          sql(s"CREATE index i1 ON $catalogName.new_table USING $indexType (col1)")
        }.getMessage
        assert(m.contains(s"Index Type $indexType is not supported." +
          s" The supported Index Types are:"))

        sql(s"CREATE index i1 ON $catalogName.new_table USING BTREE (col1)")
        assert(jdbcTable.indexExists("i1"))
        if (supportListIndexes) {
          val indexes = jdbcTable.listIndexes()
          assert(indexes.size == 1)
          assert(indexes.head.indexName() == "i1")
        }

        sql(s"CREATE index i2 ON $catalogName.new_table (col2, col3, col5)" +
          s" OPTIONS ($indexOptions)")
        assert(jdbcTable.indexExists("i2"))
        if (supportListIndexes) {
          val indexes = jdbcTable.listIndexes()
          assert(indexes.size == 2)
          assert(indexes.map(_.indexName()).sorted === Array("i1", "i2"))
        }

        // This should pass without exception
        sql(s"CREATE index IF NOT EXISTS i1 ON $catalogName.new_table (col1)")

        checkError(
          exception = intercept[IndexAlreadyExistsException] {
            sql(s"CREATE index i1 ON $catalogName.new_table (col1)")
          },
          condition = "INDEX_ALREADY_EXISTS",
          parameters = Map("indexName" -> "`i1`", "tableName" -> "`new_table`")
        )

        sql(s"DROP index i1 ON $catalogName.new_table")
        assert(jdbcTable.indexExists("i1") == false)
        if (supportListIndexes) {
          val indexes = jdbcTable.listIndexes()
          assert(indexes.size == 1)
          assert(indexes.head.indexName() == "i2")
        }

        sql(s"DROP index i2 ON $catalogName.new_table")
        assert(jdbcTable.indexExists("i2") == false)
        if (supportListIndexes) {
          assert(jdbcTable.listIndexes().isEmpty)
        }

        // This should pass without exception
        sql(s"DROP index IF EXISTS i1 ON $catalogName.new_table")

        checkError(
          exception = intercept[NoSuchIndexException] {
            sql(s"DROP index i1 ON $catalogName.new_table")
          },
          condition = "INDEX_NOT_FOUND",
          parameters = Map("indexName" -> "`i1`", "tableName" -> "`new_table`")
        )
      }
    }
  }

  def supportsTableSample: Boolean = false

  test("SPARK-48172: Test CONTAINS") {
    val df1 = spark.sql(
      s"""
         |SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE contains(pattern_testing_col, 'quote\\'')""".stripMargin)
    df1.explain("formatted")
    val rows1 = df1.collect()
    assert(rows1.length === 1)
    assert(rows1(0).getString(0) === "special_character_quote'_present")

    val df2 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE contains(pattern_testing_col, 'percent%')""".stripMargin)
    val rows2 = df2.collect()
    assert(rows2.length === 1)
    assert(rows2(0).getString(0) === "special_character_percent%_present")

    val df3 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE contains(pattern_testing_col, 'underscore_')""".stripMargin)
    val rows3 = df3.collect()
    assert(rows3.length === 1)
    assert(rows3(0).getString(0) === "special_character_underscore_present")

    val df4 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE contains(pattern_testing_col, 'character')
           |ORDER BY pattern_testing_col""".stripMargin)
    val rows4 = df4.collect()
    assert(rows4.length === 6)
    assert(rows4(0).getString(0) === "special_character_percent%_present")
    assert(rows4(1).getString(0) === "special_character_percent_not_present")
    assert(rows4(2).getString(0) === "special_character_quote'_present")
    assert(rows4(3).getString(0) === "special_character_quote_not_present")
    assert(rows4(4).getString(0) === "special_character_underscore_present")
    assert(rows4(5).getString(0) === "special_character_underscorenot_present")
  }

  test("SPARK-48172: Test ENDSWITH") {
    val df1 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE endswith(pattern_testing_col, 'quote\\'_present')""".stripMargin)
    val rows1 = df1.collect()
    assert(rows1.length === 1)
    assert(rows1(0).getString(0) === "special_character_quote'_present")

    val df2 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE endswith(pattern_testing_col, 'percent%_present')""".stripMargin)
    val rows2 = df2.collect()
    assert(rows2.length === 1)
    assert(rows2(0).getString(0) === "special_character_percent%_present")

    val df3 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE endswith(pattern_testing_col, 'underscore_present')""".stripMargin)
    val rows3 = df3.collect()
    assert(rows3.length === 1)
    assert(rows3(0).getString(0) === "special_character_underscore_present")

    val df4 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE endswith(pattern_testing_col, 'present')
           |ORDER BY pattern_testing_col""".stripMargin)
    val rows4 = df4.collect()
    assert(rows4.length === 6)
    assert(rows4(0).getString(0) === "special_character_percent%_present")
    assert(rows4(1).getString(0) === "special_character_percent_not_present")
    assert(rows4(2).getString(0) === "special_character_quote'_present")
    assert(rows4(3).getString(0) === "special_character_quote_not_present")
    assert(rows4(4).getString(0) === "special_character_underscore_present")
    assert(rows4(5).getString(0) === "special_character_underscorenot_present")
  }

  test("SPARK-48172: Test STARTSWITH") {
    val df1 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE startswith(pattern_testing_col, 'special_character_quote\\'')""".stripMargin)
    val rows1 = df1.collect()
    assert(rows1.length === 1)
    assert(rows1(0).getString(0) === "special_character_quote'_present")

    val df2 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE startswith(pattern_testing_col, 'special_character_percent%')""".stripMargin)
    val rows2 = df2.collect()
    assert(rows2.length === 1)
    assert(rows2(0).getString(0) === "special_character_percent%_present")

    val df3 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE startswith(pattern_testing_col, 'special_character_underscore_')""".stripMargin)
    val rows3 = df3.collect()
    assert(rows3.length === 1)
    assert(rows3(0).getString(0) === "special_character_underscore_present")

    val df4 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE startswith(pattern_testing_col, 'special_character')
           |ORDER BY pattern_testing_col""".stripMargin)
    val rows4 = df4.collect()
    assert(rows4.length === 6)
    assert(rows4(0).getString(0) === "special_character_percent%_present")
    assert(rows4(1).getString(0) === "special_character_percent_not_present")
    assert(rows4(2).getString(0) === "special_character_quote'_present")
    assert(rows4(3).getString(0) === "special_character_quote_not_present")
    assert(rows4(4).getString(0) === "special_character_underscore_present")
    assert(rows4(5).getString(0) === "special_character_underscorenot_present")
  }

  test("SPARK-48172: Test LIKE") {
    // this one should map to contains
    val df1 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE pattern_testing_col LIKE '%quote\\'%'""".stripMargin)
    val rows1 = df1.collect()
    assert(rows1.length === 1)
    assert(rows1(0).getString(0) === "special_character_quote'_present")

    val df2 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE pattern_testing_col LIKE '%percent\\%%'""".stripMargin)
    val rows2 = df2.collect()
    assert(rows2.length === 1)
    assert(rows2(0).getString(0) === "special_character_percent%_present")

    val df3 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE pattern_testing_col LIKE '%underscore\\_%'""".stripMargin)
    val rows3 = df3.collect()
    assert(rows3.length === 1)
    assert(rows3(0).getString(0) === "special_character_underscore_present")

    val df4 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE pattern_testing_col LIKE '%character%'
           |ORDER BY pattern_testing_col""".stripMargin)
    val rows4 = df4.collect()
    assert(rows4.length === 6)
    assert(rows4(0).getString(0) === "special_character_percent%_present")
    assert(rows4(1).getString(0) === "special_character_percent_not_present")
    assert(rows4(2).getString(0) === "special_character_quote'_present")
    assert(rows4(3).getString(0) === "special_character_quote_not_present")
    assert(rows4(4).getString(0) === "special_character_underscore_present")
    assert(rows4(5).getString(0) === "special_character_underscorenot_present")

    // map to startsWith
    // this one should map to contains
    val df5 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE pattern_testing_col LIKE 'special_character_quote\\'%'""".stripMargin)
    val rows5 = df5.collect()
    assert(rows5.length === 1)
    assert(rows5(0).getString(0) === "special_character_quote'_present")

    val df6 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE pattern_testing_col LIKE 'special_character_percent\\%%'""".stripMargin)
    val rows6 = df6.collect()
    assert(rows6.length === 1)
    assert(rows6(0).getString(0) === "special_character_percent%_present")

    val df7 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE pattern_testing_col LIKE 'special_character_underscore\\_%'""".stripMargin)
    val rows7 = df7.collect()
    assert(rows7.length === 1)
    assert(rows7(0).getString(0) === "special_character_underscore_present")

    val df8 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE pattern_testing_col LIKE 'special_character%'
           |ORDER BY pattern_testing_col""".stripMargin)
    val rows8 = df8.collect()
    assert(rows8.length === 6)
    assert(rows8(0).getString(0) === "special_character_percent%_present")
    assert(rows8(1).getString(0) === "special_character_percent_not_present")
    assert(rows8(2).getString(0) === "special_character_quote'_present")
    assert(rows8(3).getString(0) === "special_character_quote_not_present")
    assert(rows8(4).getString(0) === "special_character_underscore_present")
    assert(rows8(5).getString(0) === "special_character_underscorenot_present")
    // map to endsWith
    // this one should map to contains
    val df9 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE pattern_testing_col LIKE '%quote\\'_present'""".stripMargin)
    val rows9 = df9.collect()
    assert(rows9.length === 1)
    assert(rows9(0).getString(0) === "special_character_quote'_present")

    val df10 = spark.sql(
      s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
         |WHERE pattern_testing_col LIKE '%percent\\%_present'""".stripMargin)
    val rows10 = df10.collect()
    assert(rows10.length === 1)
    assert(rows10(0).getString(0) === "special_character_percent%_present")

    val df11 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE pattern_testing_col LIKE '%underscore\\_present'""".stripMargin)
    val rows11 = df11.collect()
    assert(rows11.length === 1)
    assert(rows11(0).getString(0) === "special_character_underscore_present")

    val df12 = spark.
      sql(
        s"""SELECT * FROM $catalogAndNamespace.${caseConvert("pattern_testing_table")}
           |WHERE pattern_testing_col LIKE '%present' ORDER BY pattern_testing_col""".stripMargin)
    val rows12 = df12.collect()
    assert(rows12.length === 6)
    assert(rows12(0).getString(0) === "special_character_percent%_present")
    assert(rows12(1).getString(0) === "special_character_percent_not_present")
    assert(rows12(2).getString(0) === "special_character_quote'_present")
    assert(rows12(3).getString(0) === "special_character_quote_not_present")
    assert(rows12(4).getString(0) === "special_character_underscore_present")
    assert(rows12(5).getString(0) === "special_character_underscorenot_present")
  }

  val partitioningEnabledTestCase = Seq(true, false)
  gridTest(
    "SPARK-37038: Test TABLESAMPLE"
  )(partitioningEnabledTestCase) { partitioningEnabled =>
    if (supportsTableSample) {
      withTable(s"$catalogName.new_table") {
        sql(s"CREATE TABLE $catalogName.new_table (col1 INT, col2 INT)")
        spark.range(10).select($"id" * 2, $"id" * 2 + 1).write.insertInto(s"$catalogName.new_table")

        val (tableOptions, partitionInfo) = getTableOptions("new_table", partitioningEnabled)
        // sample push down + column pruning
        val df1 = sql(s"SELECT col1 FROM $catalogName.new_table $tableOptions " +
          s"TABLESAMPLE (BUCKET 6 OUT OF 10) REPEATABLE (12345)")
        checkSamplePushed(df1)
        checkColumnPruned(df1, "col1")
        if (partitioningEnabled) {
          multiplePartitionAdditionalCheck(df1, partitionInfo)
        }
        assert(df1.collect().length < 10)

        // sample push down only
        val df2 = sql(s"SELECT * FROM $catalogName.new_table $tableOptions " +
          s"TABLESAMPLE (50 PERCENT) REPEATABLE (12345)")
        checkSamplePushed(df2)
        if (partitioningEnabled) {
          multiplePartitionAdditionalCheck(df1, partitionInfo)
        }
        assert(df2.collect().length < 10)

        // sample(BUCKET ... OUT OF) push down + limit push down + column pruning
        val df3 = sql(s"SELECT col1 FROM $catalogName.new_table $tableOptions " +
          s"TABLESAMPLE (BUCKET 6 OUT OF 10) LIMIT 2")
        checkSamplePushed(df3)
        checkLimitPushed(df3, Some(2))
        checkColumnPruned(df3, "col1")
        if (partitioningEnabled) {
          multiplePartitionAdditionalCheck(df1, partitionInfo)
        }
        assert(df3.collect().length <= 2)

        // sample(... PERCENT) push down + limit push down + column pruning
        val df4 = sql(s"SELECT col1 FROM $catalogName.new_table $tableOptions" +
          " TABLESAMPLE (50 PERCENT) REPEATABLE (12345) LIMIT 2")
        checkSamplePushed(df4)
        checkLimitPushed(df4, Some(2))
        checkColumnPruned(df4, "col1")
        if (partitioningEnabled) {
          multiplePartitionAdditionalCheck(df1, partitionInfo)
        }
        assert(df4.collect().length <= 2)

        // sample push down + filter push down + limit push down
        val df5 = sql(s"SELECT * FROM $catalogName.new_table $tableOptions" +
          " TABLESAMPLE (BUCKET 6 OUT OF 10) WHERE col1 > 0 LIMIT 2")
        checkSamplePushed(df5)
        checkFilterPushed(df5)
        checkLimitPushed(df5, Some(2))
        if (partitioningEnabled) {
          multiplePartitionAdditionalCheck(df1, partitionInfo)
        }
        assert(df5.collect().length <= 2)

        // sample + filter + limit + column pruning
        // sample pushed down, filer/limit not pushed down, column pruned
        // Todo: push down filter/limit
        val df6 = sql(s"SELECT col1 FROM $catalogName.new_table $tableOptions" +
          " TABLESAMPLE (BUCKET 6 OUT OF 10) WHERE col1 > 0 LIMIT 2")
        checkSamplePushed(df6)
        checkFilterPushed(df6, false)
        checkLimitPushed(df6, None)
        checkColumnPruned(df6, "col1")
        if (partitioningEnabled) {
          multiplePartitionAdditionalCheck(df1, partitionInfo)
        }
        assert(df6.collect().length <= 2)

        // sample + limit
        // Push down order is sample -> filter -> limit
        // only limit is pushed down because in this test sample is after limit
        val df7 = spark.read.table(s"$catalogName.new_table").limit(2).sample(0.5)
        checkSamplePushed(df7, false)
        checkLimitPushed(df7, Some(2))

        // sample + filter
        // Push down order is sample -> filter -> limit
        // only filter is pushed down because in this test sample is after filter
        val df8 = spark.read.table(s"$catalogName.new_table").where($"col1" > 1).sample(0.5)
        checkSamplePushed(df8, false)
        checkFilterPushed(df8)
        assert(df8.collect().length < 10)
      }
    }
  }

  gridTest("simple scan")(partitioningEnabledTestCase) { partitioningEnabled =>
    val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
    val df = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
      s"${caseConvert("employee")} $tableOptions")
    checkFilterPushed(df)
    if (partitioningEnabled) {
      multiplePartitionAdditionalCheck(df, partitionInfo)
    }
    val rows = df.collect()
    assert(rows.length == 5)
  }

  gridTest("simple scan with LIMIT")(partitioningEnabledTestCase) { partitioningEnabled =>
    val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
    val df = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
      s"${caseConvert("employee")} $tableOptions WHERE dept > 0 LIMIT 1")
    checkLimitPushed(df, Some(1))
    if (partitioningEnabled) {
      multiplePartitionAdditionalCheck(df, partitionInfo)
    }
    val rows = df.collect()
    assert(rows.length === 1)
    assert(rows(0).getString(0) === "amy")
    assert(rows(0).getDecimal(1) === new java.math.BigDecimal("10000.00"))
    assert(rows(0).getDouble(2) === 1000d)
  }

  gridTest("simple scan with top N")(partitioningEnabledTestCase) { partitioningEnabled =>
    Seq(NullOrdering.values()).flatten.foreach { nullOrdering =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df1 = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
        s"${caseConvert("employee")} $tableOptions " +
        s"WHERE dept > 0 ORDER BY salary $nullOrdering LIMIT 1")
      if (partitioningEnabled) {
        checkLimitRemoved(df1, false)
        checkSortRemoved(df1, false)
        multiplePartitionAdditionalCheck(df1, partitionInfo)
      } else {
        checkLimitPushed(df1, Some(1))
        checkSortRemoved(df1)
      }
      val rows1 = df1.collect()
      assert(rows1.length === 1)
      assert(rows1(0).getString(0) === "cathy")
      assert(rows1(0).getDecimal(1) === new java.math.BigDecimal("9000.00"))
      assert(rows1(0).getDouble(2) === 1200d)

      val df2 = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
        s"${caseConvert("employee")} $tableOptions " +
        s"WHERE dept > 0 ORDER BY bonus DESC $nullOrdering LIMIT 1")
      if (partitioningEnabled) {
        checkLimitRemoved(df2, false)
        checkSortRemoved(df2, false)
        multiplePartitionAdditionalCheck(df2, partitionInfo)
      } else {
        checkLimitPushed(df2, Some(1))
        checkSortRemoved(df2)
      }
      val rows2 = df2.collect()
      assert(rows2.length === 1)
      assert(rows2(0).getString(0) === "david")
      assert(rows2(0).getDecimal(1) === new java.math.BigDecimal("10000.00"))
      assert(rows2(0).getDouble(2) === 1300d)
    }
  }

  gridTest("simple scan with OFFSET")(partitioningEnabledTestCase) { partitioningEnabled =>
    val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
    val df = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
      s"${caseConvert("employee")} $tableOptions WHERE dept > 0 OFFSET 4")
    if (partitioningEnabled) {
      // Offset is not supported when numPartitions > 1
      checkOffsetRemoved(df, false)
      multiplePartitionAdditionalCheck(df, partitionInfo)
    } else {
      checkOffsetPushed(df, Some(4))
    }
    val rows = df.collect()
    assert(rows.length === 1)
    assert(rows(0).getString(0) === "jen")
    assert(rows(0).getString(0) === "jen")
    assert(rows(0).getDecimal(1) === new java.math.BigDecimal("12000.00"))
    assert(rows(0).getDouble(2) === 1200d)
  }

  gridTest(
    "simple scan with LIMIT and OFFSET"
  )(partitioningEnabledTestCase) { partitioningEnabled =>
    val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
    val df = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
      s"${caseConvert("employee")} $tableOptions WHERE dept > 0 LIMIT 1 OFFSET 2")
    if (partitioningEnabled) {
      // Limit and Offset is not pushed down when numPartitions > 1
      checkLimitRemoved(df, false)
      checkOffsetRemoved(df, false)
      multiplePartitionAdditionalCheck(df, partitionInfo)
    } else {
      checkLimitPushed(df, Some(3))
      checkOffsetPushed(df, Some(2))
    }
    val rows = df.collect()
    assert(rows.length === 1)
    assert(rows(0).getString(0) === "cathy")
    assert(rows(0).getDecimal(1) === new java.math.BigDecimal("9000.00"))
    assert(rows(0).getDouble(2) === 1200d)
  }

  gridTest("simple scan with paging: top N and OFFSET")(partitioningEnabledTestCase) {
    partitioningEnabled =>
    Seq(NullOrdering.values()).flatten.foreach { nullOrdering =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df1 = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
        s"${caseConvert("employee")} $tableOptions" +
        s" WHERE dept > 0 ORDER BY salary $nullOrdering, bonus LIMIT 1 OFFSET 2")
      if (partitioningEnabled) {
        // Sort is not supported when numPartitions > 1 and therefore limit and offset are
        // not removed as well.
        checkLimitRemoved(df1, false)
        checkOffsetRemoved(df1, false)
        checkSortRemoved(df1, false)
        multiplePartitionAdditionalCheck(df1, partitionInfo)
      } else {
        checkLimitPushed(df1, Some(3))
        checkOffsetPushed(df1, Some(2))
        checkSortRemoved(df1)
      }
      val rows1 = df1.collect()
      assert(rows1.length === 1)
      assert(rows1(0).getString(0) === "david")
      assert(rows1(0).getDecimal(1) === new java.math.BigDecimal("10000.00"))
      assert(rows1(0).getDouble(2) === 1300d)

      val df2 = sql(s"SELECT name, salary, bonus FROM $catalogAndNamespace." +
        s"${caseConvert("employee")} $tableOptions" +
        s" WHERE dept > 0 ORDER BY salary DESC $nullOrdering, bonus LIMIT 1 OFFSET 2")

      if (tableOptions.isEmpty) {
        checkLimitPushed(df2, Some(3))
        checkOffsetPushed(df2, Some(2))
        checkSortRemoved(df2)
      } else {
        checkLimitRemoved(df2, false)
        checkOffsetRemoved(df2, false)
        checkSortRemoved(df2, false)
        multiplePartitionAdditionalCheck(df2, partitionInfo)
      }
      val rows2 = df2.collect()
      assert(rows2.length === 1)
      assert(rows2(0).getString(0) === "amy")
      assert(rows2(0).getDecimal(1) === new java.math.BigDecimal("10000.00"))
      assert(rows2(0).getDouble(2) === 1000d)
    }
  }

  protected def caseConvert(tableName: String): String = tableName

  Seq(true, false).foreach { isDistinct =>
    val distinct = if (isDistinct) "DISTINCT " else ""
    val withOrWithout = if (isDistinct) "with" else "without"

    gridTest(
      s"scan with aggregate push-down: VAR_POP $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(s"SELECT VAR_POP(${distinct}bonus) FROM $catalogAndNamespace." +
        s"${caseConvert("employee")} $tableOptions " +
        s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "VAR_POP")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 10000.0)
      assert(row(1).getDouble(0) === 2500.0)
      assert(row(2).getDouble(0) === 0.0)
    }

    gridTest(
      s"scan with aggregate push-down: VAR_SAMP $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT VAR_SAMP(${distinct}bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "VAR_SAMP")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 20000.0)
      assert(row(1).getDouble(0) === 5000.0)
      assert(row(2).isNullAt(0))
    }

    gridTest(
      s"scan with aggregate push-down: STDDEV_POP $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT STDDEV_POP(${distinct}bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "STDDEV_POP")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 100.0)
      assert(row(1).getDouble(0) === 50.0)
      assert(row(2).getDouble(0) === 0.0)
    }

    gridTest(
      s"scan with aggregate push-down: STDDEV_SAMP $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT STDDEV_SAMP(${distinct}bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "STDDEV_SAMP")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 141.4213562373095)
      assert(row(1).getDouble(0) === 70.71067811865476)
      assert(row(2).isNullAt(0))
    }

    gridTest(
      s"scan with aggregate push-down: COVAR_POP $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT COVAR_POP(${distinct}bonus, bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "COVAR_POP")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 10000.0)
      assert(row(1).getDouble(0) === 2500.0)
      assert(row(2).getDouble(0) === 0.0)
    }

    gridTest(
      s"scan with aggregate push-down: COVAR_SAMP $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT COVAR_SAMP(${distinct}bonus, bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "COVAR_SAMP")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 20000.0)
      assert(row(1).getDouble(0) === 5000.0)
      assert(row(2).isNullAt(0))
    }

    gridTest(
      s"scan with aggregate push-down: CORR $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT CORR(${distinct}bonus, bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "CORR")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 1.0)
      assert(row(1).getDouble(0) === 1.0)
      assert(row(2).isNullAt(0))
    }

    gridTest(
      s"scan with aggregate push-down: REGR_INTERCEPT $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT REGR_INTERCEPT(${distinct}bonus, bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "REGR_INTERCEPT")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 0.0)
      assert(row(1).getDouble(0) === 0.0)
      assert(row(2).isNullAt(0))
    }

    gridTest(
      s"scan with aggregate push-down: REGR_SLOPE $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT REGR_SLOPE(${distinct}bonus, bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "REGR_SLOPE")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 1.0)
      assert(row(1).getDouble(0) === 1.0)
      assert(row(2).isNullAt(0))
    }

    gridTest(
      s"scan with aggregate push-down: REGR_R2 $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT REGR_R2(${distinct}bonus, bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "REGR_R2")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 1.0)
      assert(row(1).getDouble(0) === 1.0)
      assert(row(2).isNullAt(0))
    }

    gridTest(
      s"scan with aggregate push-down: REGR_SXY $withOrWithout DISTINCT"
    )(partitioningEnabledTestCase) { partitioningEnabled =>
      val (tableOptions, partitionInfo) = getTableOptions("employee", partitioningEnabled)
      val df = sql(
        s"SELECT REGR_SXY(${distinct}bonus, bonus) FROM $catalogAndNamespace." +
          s"${caseConvert("employee")} $tableOptions " +
          s"WHERE dept > 0 GROUP BY dept ORDER BY dept")
      checkFilterPushed(df)
      checkAggregateRemoved(df)
      checkAggregatePushed(df, "REGR_SXY")
      if (partitioningEnabled) {
        multiplePartitionAdditionalCheck(df, partitionInfo)
      }
      val row = df.collect()
      assert(row.length === 3)
      assert(row(0).getDouble(0) === 20000.0)
      assert(row(1).getDouble(0) === 5000.0)
      assert(row(2).getDouble(0) === 0.0)
    }
  }

  test("SPARK-48618: Renaming the table to the name of an existing table") {
    withTable(s"$catalogName.tbl1", s"$catalogName.tbl2") {
      sql(s"CREATE TABLE $catalogName.tbl1 (col1 INT, col2 INT)")
      sql(s"CREATE TABLE $catalogName.tbl2 (col3 INT, col4 INT)")

      checkError(
        exception = intercept[AnalysisException] {
          sql(s"ALTER TABLE $catalogName.tbl2 RENAME TO tbl1")
        },
        condition = "TABLE_OR_VIEW_ALREADY_EXISTS",
        parameters = Map("relationName" -> "`tbl1`")
      )
    }
  }

  test("SPARK-48618: Test table does not exists error") {
    val tbl = s"$catalogName.tbl1"
    val sqlStatement = s"SELECT * FROM $tbl"
    val startPos = sqlStatement.indexOf(tbl)

    withTable(tbl) {
      sql(s"CREATE TABLE $tbl (col1 INT, col2 INT)")
      sql(s"INSERT INTO $tbl VALUES (1, 2)")
      val df = sql(sqlStatement)
      val row = df.collect()
      assert(row.length === 1)

      // Drop the table
      sql(s"DROP TABLE IF EXISTS $tbl")

      checkError(
        exception = intercept[AnalysisException] {
          sql(sqlStatement).collect()
        },
        condition = "TABLE_OR_VIEW_NOT_FOUND",
        parameters = Map("relationName" -> s"`$catalogName`.`tbl1`"),
        context = ExpectedContext(tbl, startPos, startPos + tbl.length - 1)
      )
    }
  }

  def testDatetime(tbl: String): Unit = {}

  test("scan with filter push-down with date time functions") {
    testDatetime(s"$catalogAndNamespace.${caseConvert("datetime")}")
  }

  test("SPARK-50792: Format binary data as a binary literal in JDBC.") {
    val tableName = s"$catalogName.test_binary_literal"
    withTable(tableName) {
      // Create a table with binary column
      val binary = "X'123456'"
      val lessThanBinary = "X'123455'"
      val greaterThanBinary = "X'123457'"

      sql(s"CREATE TABLE $tableName (binary_col BINARY)")
      sql(s"INSERT INTO $tableName VALUES ($binary)")

      def testBinaryLiteral(operator: String, literal: String, expected: Int): Unit = {
        val sql = s"SELECT * FROM $tableName WHERE binary_col $operator $literal"
        val df = spark.sql(sql)
        checkFilterPushed(df)
        val rows = df.collect()
        assert(rows.length === expected, s"Failed to run $sql")
        if (expected == 1) {
          assert(rows(0)(0) === Array(0x12, 0x34, 0x56).map(_.toByte))
        }
      }

      testBinaryLiteral("=", binary, 1)
      testBinaryLiteral(">=", binary, 1)
      testBinaryLiteral(">=", lessThanBinary, 1)
      testBinaryLiteral(">", lessThanBinary, 1)
      testBinaryLiteral("<=", binary, 1)
      testBinaryLiteral("<=", greaterThanBinary, 1)
      testBinaryLiteral("<", greaterThanBinary, 1)
      testBinaryLiteral("<>", greaterThanBinary, 1)
      testBinaryLiteral("<>", lessThanBinary, 1)
      testBinaryLiteral("<=>", binary, 1)
      testBinaryLiteral("<=>", lessThanBinary, 0)
      testBinaryLiteral("<=>", greaterThanBinary, 0)
    }
  }

  test("SPARK-52262: FAILED_JDBC.TABLE_EXISTS not thrown on connection error") {
    val invalidTableName = s"$catalogName.invalid"
    val originalUrl = spark.conf.get(s"spark.sql.catalog.$catalogName.url")
    val invalidUrl = originalUrl.replace("localhost", "nonexistenthost")
      .replace("127.0.0.1", "1.2.3.4")

    withSQLConf(s"spark.sql.catalog.$catalogName.url" -> invalidUrl) {
      // Ideally we would catch SQLException, but analyzer wraps it
      val e = intercept[AnalysisException] {
        sql(s"SELECT * FROM $invalidTableName")
      }
      assert(e.getCondition !== "FAILED_JDBC.TABLE_EXISTS")
    }
  }
}
