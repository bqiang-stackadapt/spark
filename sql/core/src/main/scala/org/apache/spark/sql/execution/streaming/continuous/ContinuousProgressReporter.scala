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

package org.apache.spark.sql.execution.streaming.continuous

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.rpc.RpcEndpointRef
import org.apache.spark.sql.catalyst.plans.logical.EventTimeWatermark
import org.apache.spark.sql.catalyst.util.DateTimeConstants.MILLIS_PER_SECOND
import org.apache.spark.sql.connector.read.streaming.SparkDataStream
import org.apache.spark.sql.execution.streaming.{EventTimeWatermarkExec, ProgressReporter, StreamProgress}
import org.apache.spark.sql.streaming.{SinkProgress, SourceProgress, StreamingQueryProgress}

trait ContinuousProgressReporter extends ProgressReporter {

  protected def epochEndpoint: RpcEndpointRef

  private var earliestEpochId: Long = -1

  private val triggerStartTimestamps = new mutable.HashMap[Long, Long]()
  private val recordDurationMs = new mutable.HashMap[String, Long]()

  private val currentTriggerStartOffsets: mutable.HashMap[Long, Map[SparkDataStream, String]] =
    new mutable.HashMap[Long, Map[SparkDataStream, String]]()
  private val currentTriggerEndOffsets: mutable.HashMap[Long, Map[SparkDataStream, String]] =
    new mutable.HashMap[Long, Map[SparkDataStream, String]]()
  private val currentTriggerLatestOffsets: mutable.HashMap[Long, Map[SparkDataStream, String]] =
    new mutable.HashMap[Long, Map[SparkDataStream, String]]()

  private var lastCommittedTriggerEndTimestamp = -1L
  private var lastTriggerStartTimestamp = -1L

  private val noDataProgressEventInterval =
    sparkSession.sessionState.conf.streamingNoDataProgressEventInterval

  // The timestamp we report an event that has no input data
  private var lastNoDataProgressEventTime = Long.MinValue

  private val numProgressRetention: Int = sparkSession.sqlContext.conf.streamingProgressRetention

  /** Begins recording statistics about query progress for a given trigger. */
  override protected def startTrigger(): Unit = {
    logDebug("Starting Trigger Calculation")
    if (earliestEpochId == -1) {
      earliestEpochId = currentBatchId
    }
    checkQueueBoundaries()
    triggerStartTimestamps.put(currentBatchId, triggerClock.getTimeMillis())
  }

  protected def extractExecutionStats(hasNewData: Boolean,
                                      hasExecuted: Boolean,
                                      extraInfos: Option[Any] = None): ExecutionStats = {
    val hasEventTime = logicalPlan.collect { case e: EventTimeWatermark => e }.nonEmpty
    val watermarkTimestamp =
      if (hasEventTime) Map("watermark" -> formatTimestamp(offsetSeqMetadata.batchWatermarkMs))
      else Map.empty[String, String]

    // SPARK-19378: Still report metrics even though no data was processed while reporting progress.
    val stateOperators = extractStateOperatorMetrics(hasExecuted)

    if (!hasNewData) {
      return ExecutionStats(Map.empty, stateOperators, watermarkTimestamp)
    }

    val numInputRows = extractSourceToNumInputRows(extraInfos)

    val eventTimeStats = lastExecution.executedPlan.collect {
      case e: EventTimeWatermarkExec if e.eventTimeStats.value.count > 0 =>
        val stats = e.eventTimeStats.value
        Map(
          "max" -> stats.max,
          "min" -> stats.min,
          "avg" -> stats.avg.toLong).mapValues(formatTimestamp)
    }.headOption.getOrElse(Map.empty) ++ watermarkTimestamp

    ExecutionStats(numInputRows, stateOperators, eventTimeStats.toMap)
  }

  /** Finalizes the query progress and adds it to list of recent status updates. */
  protected def finishTrigger(hasNewData: Boolean,
                              hasExecuted: Boolean,
                              epochId: Long,
                              epochStats: EpochStats): Unit = {
    assert(currentTriggerStartOffsets.contains(epochId)
      && currentTriggerEndOffsets.contains(epochId)
      && currentTriggerLatestOffsets.contains(epochId)
      && triggerStartTimestamps.contains(epochId))

    val currentTriggerStartTimestamp = triggerStartTimestamps(epochId)
    val currentTriggerEndTimestamp = triggerClock.getTimeMillis()

    val executionStats = extractExecutionStats(hasNewData, hasExecuted, Some(epochStats))

    val processingTimeSec = if (lastCommittedTriggerEndTimestamp >= 0) {
      (currentTriggerEndTimestamp - lastCommittedTriggerEndTimestamp).toDouble / MILLIS_PER_SECOND
    } else {
      Double.NaN
    }

    val batchDuration = if (lastCommittedTriggerEndTimestamp >= 0) {
      currentTriggerEndTimestamp - lastCommittedTriggerEndTimestamp
    } else {
      0
    }

    // for the latency metric
    if (processingTimeSec != Double.NaN) {
      recordDurationMs.put("triggerExecution",
        currentTriggerEndTimestamp - lastCommittedTriggerEndTimestamp)
    }

    lastTriggerStartTimestamp = triggerStartTimestamps.getOrElse(epochId-1, -1L)
    lastCommittedTriggerEndTimestamp = currentTriggerEndTimestamp

    val inputTimeSec = if (lastTriggerStartTimestamp >= 0) {
      (currentTriggerStartTimestamp - lastTriggerStartTimestamp).toDouble / MILLIS_PER_SECOND
    } else {
      Double.NaN
    }

    val sourceProgress = sources.distinct.map { source =>
      val numRecords = executionStats.inputRows.getOrElse(source, 0L)
      new SourceProgress(
        description = source.toString,
        startOffset = currentTriggerStartOffsets(epochId).get(source).orNull,
        endOffset = currentTriggerEndOffsets(epochId).get(source).orNull,
        numInputRows = numRecords,
        inputRowsPerSecond = numRecords / inputTimeSec,
        processedRowsPerSecond = numRecords / processingTimeSec,
        latestOffset = currentTriggerLatestOffsets(epochId).get(source).orNull
      )
    }

    val sinkProgress = SinkProgress(
      sink.toString,
      sinkCommitProgress.map(_.numOutputRows)
    )

    val newProgress = new StreamingQueryProgress(
      id = id,
      runId = runId,
      name = name,
      timestamp = formatTimestamp(currentTriggerStartTimestamp),
      batchId = epochId,
      durationMs = new java.util.HashMap(recordDurationMs.toMap.mapValues(long2Long).asJava),
      eventTime = new java.util.HashMap(executionStats.eventTimeStats.asJava),
      stateOperators = executionStats.stateOperators.toArray,
      sources = sourceProgress.toArray,
      sink = sinkProgress,
      batchDuration = batchDuration,
      observedMetrics = new java.util.HashMap(Map.empty.asJava)
    )

    if (hasNewData) {
      // Reset noDataEventTimestamp if we processed any data
      lastNoDataProgressEventTime = Long.MinValue
      updateProgress(newProgress)
    } else {
      val now = triggerClock.getTimeMillis()
      if (now - noDataProgressEventInterval >= lastNoDataProgressEventTime) {
        lastNoDataProgressEventTime = now
        updateProgress(newProgress)
      }
    }

    currentStatus = currentStatus.copy(isTriggerActive = false)
  }

  protected def extractSourceToNumInputRows(t: Option[Any] = None)
  : Map[SparkDataStream, Long] = {
    require(t.isDefined && t.get.isInstanceOf[EpochStats])
    Map(sources.head -> t.get.asInstanceOf[EpochStats].inputRows)
  }

  /**
   * Record the offsets range this trigger will process. Call this before updating
   * `committedOffsets` in `StreamExecution` to make sure that the correct range is recorded.
   */
  protected def recordTriggerOffsets(
                                               from: StreamProgress,
                                               to: StreamProgress,
                                               latest: StreamProgress,
                                               epochId: Long): Unit = {
    checkQueueBoundaries()
    currentTriggerStartOffsets.put(epochId, from.mapValues(_.json))
    currentTriggerEndOffsets.put(epochId, to.mapValues(_.json))
    currentTriggerLatestOffsets.put(epochId, latest.mapValues(_.json))
  }

  private def checkQueueBoundaries(): Unit = {
    if (triggerStartTimestamps.size > numProgressRetention) {
      val smallestKey = triggerStartTimestamps.keys.min
      triggerStartTimestamps.remove(smallestKey)
    }

    if (currentTriggerStartOffsets.size > numProgressRetention) {
      val smallestKey = currentTriggerStartOffsets.keys.min
      currentTriggerStartOffsets.remove(smallestKey)
    }

    if (currentTriggerEndOffsets.size > numProgressRetention) {
      val smallestKey = currentTriggerEndOffsets.keys.min
      currentTriggerEndOffsets.remove(smallestKey)
    }

    if (currentTriggerLatestOffsets.size > numProgressRetention) {
      val smallestKey = currentTriggerLatestOffsets.keys.min
      currentTriggerLatestOffsets.remove(smallestKey)
    }
  }
}
