/*
 * Copyright (c) 2023, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import ai.rapids.cudf.{Rmm, RmmAllocationMode, RmmEventHandler, Table}
import com.nvidia.spark.rapids.RmmRapidsRetryIterator.{withRetry, withRetryNoSplit}
import com.nvidia.spark.rapids.jni.{RmmSpark, SplitAndRetryOOM}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.FunSuite
import org.scalatest.mockito.MockitoSugar

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DataType, LongType}

class WithRetrySuite
    extends FunSuite
        with BeforeAndAfterEach with MockitoSugar with Arm {

  private def buildBatch: SpillableColumnarBatch = {
    val reductionTable = new Table.TestBuilder()
        .column(5L, null.asInstanceOf[java.lang.Long], 3L, 1L)
        .build()
    withResource(reductionTable) { tbl =>
      val cb = GpuColumnVector.from(tbl, Seq(LongType).toArray[DataType])
      spy(SpillableColumnarBatch(cb, -1, RapidsBuffer.defaultSpillCallback))
    }
  }
  
  private var rmmWasInitialized = false

  override def beforeEach(): Unit = {
    SparkSession.getActiveSession.foreach(_.stop())
    SparkSession.clearActiveSession()
    if (!Rmm.isInitialized) {
      rmmWasInitialized = true
      Rmm.initialize(RmmAllocationMode.CUDA_DEFAULT, null, 512 * 1024 * 1024)
    }
    val deviceStorage = new RapidsDeviceMemoryStore()
    val catalog = new RapidsBufferCatalog(deviceStorage)
    RapidsBufferCatalog.setCatalog(catalog)
    val mockEventHandler = new BaseRmmEventHandler()
    RmmSpark.setEventHandler(mockEventHandler)
    RmmSpark.associateThreadWithTask(RmmSpark.getCurrentThreadId, 1)
  }

  override def afterEach(): Unit = {
    RmmSpark.removeThreadAssociation(RmmSpark.getCurrentThreadId)
    RmmSpark.clearEventHandler()
    RapidsBufferCatalog.close()
    if (rmmWasInitialized) {
      Rmm.shutdown()
    }
  }

  test("withRetry closes input on failure") {
    val myItems = Seq(buildBatch, buildBatch)
    assertThrows[IllegalStateException] {
      try {
        withRetry(myItems.iterator, splitPolicy = null) { _ =>
          throw new IllegalStateException("unhandled exception")
        }.toSeq
      } finally {
        // verify that close was called on the first item,
        // which was attempted, but not the second
        verify(myItems.head, times(1)).close()
        verify(myItems.last, times(0)).close()
        myItems(1).close()
      }
    }
  }

  test("withRetryNoSplit closes input on failure") {
    val myItems = Seq(buildBatch, buildBatch)
    assertThrows[IllegalStateException] {
      try {
        withRetryNoSplit(myItems) { _ =>
          throw new IllegalStateException("unhandled exception")
        }
      } finally {
        myItems.foreach { item =>
          // verify that close was called
          verify(item, times(1)).close()
        }
      }
    }
  }

  test("withRetry closes input and attempts on failure") {
    val myItems = Seq(buildBatch, buildBatch)
    val myAttempts = Seq(buildBatch, buildBatch)
    val mockSplitPolicy = (toSplit: SpillableColumnarBatch) => {
      withResource(toSplit) { _ =>
        myAttempts
      }
    }
    assertThrows[IllegalStateException] {
      try {
        var didThrow = false
        withRetry(myItems.iterator, mockSplitPolicy) { _ =>
          if (!didThrow) {
            didThrow = true
            throw new SplitAndRetryOOM("in tests")
          } else {
            throw new IllegalStateException("unhandled exception")
          }
        }.toSeq
      } finally {
        myAttempts.foreach { item =>
          // verify that close was called on all attempts
          verify(item, times(1)).close()
        }
        verify(myItems.head, times(1)).close()
        verify(myItems.last, times(0)).close()
        myItems(1).close()
      }
    }
  }

  test("withRetry closes input on missing split policy") {
    val myItems = Seq(buildBatch, buildBatch)
    assertThrows[OutOfMemoryError] {
      try {
        withRetry(myItems.iterator, splitPolicy = null) { _ =>
          throw new SplitAndRetryOOM("unhandled split-and-retry")
        }.toSeq
      } finally {
        verify(myItems.head, times(1)).close()
        verify(myItems.last, times(0)).close()
        myItems(1).close()
      }
    }
  }

  private class BaseRmmEventHandler extends RmmEventHandler {
    override def getAllocThresholds: Array[Long] = null
    override def getDeallocThresholds: Array[Long] = null
    override def onAllocThreshold(totalAllocSize: Long): Unit = {}
    override def onDeallocThreshold(totalAllocSize: Long): Unit = {}
    override def onAllocFailure(sizeRequested: Long, retryCount: Int): Boolean = {
      false
    }
  }
}