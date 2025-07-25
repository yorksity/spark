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

package org.apache.spark.sql.catalyst.expressions

import org.apache.datasketches.frequencies.ItemsSketch
import org.apache.datasketches.memory.Memory

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.{TypeCheckFailure, TypeCheckSuccess}
import org.apache.spark.sql.catalyst.expressions.aggregate.ApproxTopK
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.types._

/**
 * An expression that estimates the top K items from a sketch.
 *
 * The input is a sketch state that is generated by the ApproxTopKAccumulation function.
 * The output is an array of structs, each containing a frequent item and its estimated frequency.
 * The items are sorted by their estimated frequency in descending order.
 *
 * @param state The sketch state, which is a struct containing the serialized sketch data,
 *              the original data type and the max items tracked of the sketch.
 * @param k     The number of top items to estimate.
 */
// scalastyle:off line.size.limit
@ExpressionDescription(
  usage = """
    _FUNC_(state, k) - Returns top k items with their frequency.
      `k` An optional INTEGER literal greater than 0. If k is not specified, it defaults to 5.
  """,
  examples = """
    Examples:
      > SELECT _FUNC_(approx_top_k_accumulate(expr)) FROM VALUES (0), (0), (1), (1), (2), (3), (4), (4) AS tab(expr);
       [{"item":0,"count":2},{"item":4,"count":2},{"item":1,"count":2},{"item":2,"count":1},{"item":3,"count":1}]

      > SELECT _FUNC_(approx_top_k_accumulate(expr), 2) FROM VALUES 'a', 'b', 'c', 'c', 'c', 'c', 'd', 'd' tab(expr);
       [{"item":"c","count":4},{"item":"d","count":2}]
  """,
  group = "misc_funcs",
  since = "4.1.0")
// scalastyle:on line.size.limit
case class ApproxTopKEstimate(state: Expression, k: Expression)
  extends BinaryExpression
  with CodegenFallback
  with ImplicitCastInputTypes {

  def this(child: Expression, topK: Int) = this(child, Literal(topK))

  def this(child: Expression) = this(child, Literal(ApproxTopK.DEFAULT_K))

  private lazy val itemDataType: DataType = {
    // itemDataType is the type of the second field of the output of ACCUMULATE or COMBINE
    state.dataType.asInstanceOf[StructType](1).dataType
  }

  override def left: Expression = state

  override def right: Expression = k

  override def inputTypes: Seq[AbstractDataType] = Seq(StructType, IntegerType)

  private def checkStateFieldAndType(state: Expression): TypeCheckResult = {
    val stateStructType = state.dataType.asInstanceOf[StructType]
    if (stateStructType.length != 3) {
      return TypeCheckFailure("State must be a struct with 3 fields. " +
        "Expected struct: struct<sketch:binary,itemDataType:any,maxItemsTracked:int>. " +
        "Got: " + state.dataType.simpleString)
    }

    if (stateStructType.head.dataType != BinaryType) {
      TypeCheckFailure("State struct must have the first field to be binary. " +
        "Got: " + stateStructType.head.dataType.simpleString)
    } else if (!ApproxTopK.isDataTypeSupported(itemDataType)) {
      TypeCheckFailure("State struct must have the second field to be a supported data type. " +
        "Got: " + itemDataType.simpleString)
    } else if (stateStructType(2).dataType != IntegerType) {
      TypeCheckFailure("State struct must have the third field to be int. " +
        "Got: " + stateStructType(2).dataType.simpleString)
    } else {
      TypeCheckSuccess
    }
  }


  override def checkInputDataTypes(): TypeCheckResult = {
    val defaultCheck = super.checkInputDataTypes()
    if (defaultCheck.isFailure) {
      defaultCheck
    } else {
      val stateCheck = checkStateFieldAndType(state)
      if (stateCheck.isFailure) {
        stateCheck
      } else if (!k.foldable) {
        TypeCheckFailure("K must be a constant literal")
      } else {
        TypeCheckSuccess
      }
    }
  }

  override def dataType: DataType = ApproxTopK.getResultDataType(itemDataType)

  override def eval(input: InternalRow): Any = {
    // null check
    ApproxTopK.checkExpressionNotNull(k, "k")
    // eval
    val stateEval = left.eval(input)
    val kEval = right.eval(input)
    val dataSketchBytes = stateEval.asInstanceOf[InternalRow].getBinary(0)
    val maxItemsTrackedVal = stateEval.asInstanceOf[InternalRow].getInt(2)
    val kVal = kEval.asInstanceOf[Int]
    ApproxTopK.checkK(kVal)
    ApproxTopK.checkMaxItemsTracked(maxItemsTrackedVal, kVal)
    val itemsSketch = ItemsSketch.getInstance(
      Memory.wrap(dataSketchBytes), ApproxTopK.genSketchSerDe(itemDataType))
    ApproxTopK.genEvalResult(itemsSketch, kVal, itemDataType)
  }

  override protected def withNewChildrenInternal(newState: Expression, newK: Expression)
  : Expression = copy(state = newState, k = newK)

  override def nullable: Boolean = false

  override def prettyName: String =
    getTagValue(FunctionRegistry.FUNC_ALIAS).getOrElse("approx_top_k_estimate")
}
