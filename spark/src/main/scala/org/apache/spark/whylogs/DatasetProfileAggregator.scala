package org.apache.spark.whylogs

import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.{Collections, UUID}

import com.whylogs.core.DatasetProfile
import com.whylogs.spark.ClassificationMetricsSession
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.expressions.Aggregator
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.{Encoder, Encoders, Row}

import scala.collection.JavaConverters._

object InstantDateTimeFormatter {
  private val Formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC)

  def format(instant: Instant): String = {
    Formatter.format(instant)
  }
}

/**
 * A dataset aggregator. It aggregates [[Row]] into DatasetProfile objects
 * underneath the hood.
 *
 */
case class DatasetProfileAggregator(datasetName: String,
                                    sessionTimeInMillis: Long,
                                    timeColumn: String = null,
                                    groupByColumns: Seq[String] = Seq(),
                                    classificationMetrics: ClassificationMetricsSession = null,
                                    sessionId: String = UUID.randomUUID().toString)
  extends Aggregator[Row, DatasetProfile, Array[Byte]] with Serializable {

  private val allGroupByColumns = (groupByColumns ++ Option(timeColumn).toSeq).toSet

  override def zero: DatasetProfile = new DatasetProfile(sessionId, Instant.ofEpochMilli(0))

  override def reduce(profile: DatasetProfile, row: Row): DatasetProfile = {
    val schema = row.schema

    val dataTimestamp = Option(timeColumn)
      .map(schema.fieldIndex)
      .map(row.getTimestamp)
      .map(_.toInstant)

    val dataTimestampString = dataTimestamp.map(InstantDateTimeFormatter.format)

    val tags = getTagsFromRow(row) ++
      dataTimestampString.map(value => (timeColumn, value)).toMap ++
      Map("Name" -> datasetName)

    val timedProfile: DatasetProfile = dataTimestamp match {
      case None if isProfileEmpty(profile) =>
        // we have an empty profile
        new DatasetProfile(sessionId,
          Instant.ofEpochMilli(sessionTimeInMillis),
          null,
          tags.asJava,
          Collections.emptyMap())
      case Some(ts) if isProfileEmpty(profile) =>
        // create a new profile to replace the empty profile
        new DatasetProfile(sessionId,
          Instant.ofEpochMilli(sessionTimeInMillis),
          ts,
          tags.asJava,
          Collections.emptyMap())
      case Some(ts) if ts != profile.getDataTimestamp =>
        throw new IllegalStateException(s"Mismatched session timestamp. " +
          s"Previously seen ts: [${profile.getDataTimestamp}]. Current session timestamp: $ts")
      case _ =>
        // ensure tags match
        if (profile.getTags != tags.asJava) {
          throw new IllegalStateException(s"Mismatched grouping columns. " +
            s"Previously seen values: ${profile.getTags}. Current values: ${tags.asJava}")
        }

        profile
    }

    // TODO: we have the schema here. Support schema?
    for (field: StructField <- schema) {
      if (!allGroupByColumns.contains(field.name)) {
        timedProfile.track(field.name, row.get(schema.fieldIndex(field.name)))
      }
    }

    if (classificationMetrics != null) {
      val classificationDatasetProfile = timedProfile.withClassificationMetrics(classificationMetrics.labels.asJava)
      val prediction = row.get(schema.fieldIndex(classificationMetrics.predictionField))
      val target = row.get(schema.fieldIndex(classificationMetrics.targetField))

      val score = if (classificationMetrics.scoreField != null) {
        row.getDouble(schema.fieldIndex(classificationMetrics.targetField))
      } else {
        1.0
      }
      classificationDatasetProfile.trackClassificationMetrics(prediction, target, score)

      classificationDatasetProfile
    } else {
      timedProfile
    }
  }

  private def isProfileEmpty(profile: DatasetProfile) = {
    profile.getDataTimestamp == null && profile.getColumns.isEmpty
  }

  private def getTagsFromRow(row: Row): Map[String, String] = {
    val schema = row.schema
    groupByColumns
      .map(col => (col, schema.fieldIndex(col)))
      .map(idxCol => (idxCol._1, Option(row.get(idxCol._2)).map(_.toString).getOrElse("")))
      .toMap
  }

  override def merge(profile1: DatasetProfile, profile2: DatasetProfile): DatasetProfile = {
    if (profile1.getColumns.isEmpty) return profile2
    if (profile2.getColumns.isEmpty) return profile1

    profile1.merge(profile2)
  }

  override def finish(reduction: DatasetProfile): Array[Byte] = {
    val finalProfile = new DatasetProfile(
      datasetName,
      reduction.getSessionTimestamp,
      reduction.getDataTimestamp,
      reduction.getColumns,
      reduction.getTags,
      reduction.getMetadata,
      reduction.getClassificationMetrics,
    )
    val msg = finalProfile.toProtobuf.build()
    val bos = new ByteArrayOutputStream(msg.getSerializedSize)
    msg.writeDelimitedTo(bos)
    bos.toByteArray
  }

  override def bufferEncoder: Encoder[DatasetProfile] = Encoders.javaSerialization(classOf[DatasetProfile])

  override def outputEncoder: Encoder[Array[Byte]] = ExpressionEncoder[Array[Byte]]()
}
