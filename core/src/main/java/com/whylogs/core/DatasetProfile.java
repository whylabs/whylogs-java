package com.whylogs.core;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import com.whylogs.core.iterator.ColumnsChunkSegmentIterator;
import com.whylogs.core.message.ColumnMessage;
import com.whylogs.core.message.ColumnSummary;
import com.whylogs.core.message.ColumnsChunkSegment;
import com.whylogs.core.message.DatasetMetadataSegment;
import com.whylogs.core.message.DatasetProfileMessage;
import com.whylogs.core.message.DatasetProperties;
import com.whylogs.core.message.DatasetSummary;
import com.whylogs.core.message.MessageSegment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;
import lombok.val;

/** Representing a DatasetProfile that tracks */
@AllArgsConstructor
public class DatasetProfile implements Serializable {
  // generated by IntelliJ
  private static final long serialVersionUID = -9221998596693275458L;

  @Getter String sessionId;
  @Getter Instant sessionTimestamp;
  @Getter Instant dataTimestamp;
  Map<String, ColumnProfile> columns;
  // always sorted
  @Getter Map<String, String> tags;
  @Getter Map<String, String> metadata;

  @Nullable ModelProfile modelProfile;

  /**
   * DEVELOPER API. DO NOT USE DIRECTLY
   *
   * @param sessionId dataset name
   * @param sessionTimestamp the timestamp for the current profiling session
   * @param dataTimestamp the timestamp for the dataset. Used to aggregate across different cadences
   * @param tags tags of the dataset
   * @param columns the columns that we're copying over. Note that the source of columns should stop
   *     using these column objects as they will back this DatasetProfile instead
   */
  public DatasetProfile(
      @NonNull String sessionId,
      @NonNull Instant sessionTimestamp,
      @Nullable Instant dataTimestamp,
      @NonNull Map<String, String> tags,
      @NonNull Map<String, ColumnProfile> columns) {
    this(
        sessionId,
        sessionTimestamp,
        dataTimestamp,
        new ConcurrentHashMap<>(Optional.ofNullable(columns).orElse(Collections.emptyMap())),
        new ConcurrentHashMap<>(Optional.ofNullable(tags).orElse(Collections.emptyMap())),
        new ConcurrentHashMap<>(),
        null);
  }

  /**
   * Create a new Dataset profile
   *
   * @param sessionId the name of the dataset profile
   * @param sessionTimestamp the timestamp for this run
   * @param tags the tags to track the dataset with
   */
  public DatasetProfile(
      @NonNull String sessionId,
      @NonNull Instant sessionTimestamp,
      @NonNull Map<String, String> tags) {
    this(sessionId, sessionTimestamp, null, tags, Collections.emptyMap());
  }

  public DatasetProfile(String sessionId, Instant sessionTimestamp) {
    this(sessionId, sessionTimestamp, Collections.emptyMap());
  }

  public Map<String, ColumnProfile> getColumns() {
    return Collections.unmodifiableMap(columns);
  }

  public ModelProfile getModelProfile() {
    return modelProfile;
  }

  public DatasetProfile withMetadata(String key, String value) {
    this.metadata.put(key, value);
    return this;
  }

  public DatasetProfile withAllMetadata(Map<String, String> metadata) {
    this.metadata.putAll(metadata);
    return this;
  }

  private void validate() {
    Preconditions.checkNotNull(sessionId);
    Preconditions.checkNotNull(sessionTimestamp);
    Preconditions.checkNotNull(columns);
    Preconditions.checkNotNull(metadata);
    Preconditions.checkNotNull(tags);
  }

  public void track(String columnName, Object data) {
    trackSingleColumn(columnName, data);
  }

  private void trackSingleColumn(String columnName, Object data) {
    val columnProfile = columns.computeIfAbsent(columnName, ColumnProfile::new);
    columnProfile.track(data);
  }

  public void track(Map<String, ?> columns) {
    columns.forEach(this::track);
    if (modelProfile != null) {
      modelProfile.track(columns);
    }
  }

  /**
   * Returns a new dataset profile with the same backing datastructure. However, this new object
   * contains a ClassificationMetrics object
   *
   * @return a new DatasetProfile object
   */
  public DatasetProfile withModelProfile(
      String prediction, String target, String score, Iterable<String> additionalOutputFields) {
    val model = new ModelProfile(prediction, target, score, additionalOutputFields);
    return new DatasetProfile(
        sessionId, sessionTimestamp, dataTimestamp, columns, tags, metadata, model);
  }

  public DatasetProfile withModelProfile(String prediction, String target, String score) {
    return this.withModelProfile(prediction, target, score, Collections.emptyList());
  }

  public DatasetProfile withModelProfile(String prediction, String target) {
    return this.withModelProfile(prediction, target, Collections.emptyList());
  }

  public DatasetProfile withModelProfile(
      String prediction, String target, Iterable<String> additionalOutputFields) {
    val model = new ModelProfile(prediction, target, additionalOutputFields);
    return new DatasetProfile(
        sessionId, sessionTimestamp, dataTimestamp, columns, tags, metadata, model);
  }

  public DatasetSummary toSummary() {
    validate();

    val summaryColumns =
        columns.values().stream()
            .map(Pair::fromColumn)
            .collect(Collectors.toMap(Pair::getName, Pair::getStatistics));

    val summary =
        DatasetSummary.newBuilder()
            .setProperties(toDatasetProperties())
            .putAllColumns(summaryColumns);

    return summary.build();
  }

  public Iterator<MessageSegment> toChunkIterator() {
    validate();

    final String marker = sessionId + UUID.randomUUID().toString();

    // first segment is the metadata
    val properties = toDatasetProperties();
    val metadataBuilder =
        DatasetMetadataSegment.newBuilder().setProperties(properties).setMarker(marker);
    val metadataSegment =
        MessageSegment.newBuilder().setMarker(marker).setMetadata(metadataBuilder).build();

    // then we group the columns by size
    val chunkedColumns =
        columns.values().stream()
            .map(ColumnProfile::toProtobuf)
            .map(ColumnMessage.Builder::build)
            .iterator();

    val columnSegmentMessages =
        Iterators.<ColumnsChunkSegment, MessageSegment>transform(
            new ColumnsChunkSegmentIterator(chunkedColumns, marker),
            msg -> MessageSegment.newBuilder().setColumns(msg).build());

    return Iterators.concat(Iterators.singletonIterator(metadataSegment), columnSegmentMessages);
  }

  public DatasetProfile mergeStrict(@NonNull DatasetProfile other) {
    Preconditions.checkArgument(
        Objects.equals(this.sessionId, other.sessionId),
        String.format("Mismatched name. Current name [%s] is merged with [%s]",
            this.sessionId,
            other.sessionId));
    Preconditions.checkArgument(
        Objects.equals(this.sessionTimestamp, other.sessionTimestamp),
        String.format("Mismatched session timestamp. Current ts [%s] is merged with [%s]",
            this.sessionTimestamp,
            other.sessionTimestamp));

    Preconditions.checkArgument(
        Objects.equals(this.dataTimestamp, other.dataTimestamp),
        String.format("Mismatched data timestamp. Current ts [%s] is merged with [%s]",
            this.dataTimestamp,
            other.dataTimestamp));
    Preconditions.checkArgument(
        Objects.equals(this.tags, other.tags),
        String.format("Mismatched tags. Current %s being merged with %s",
            this.tags,
            other.tags));

    return doMerge(other, this.tags);
  }

  /**
   * Merge the data of another {@link DatasetProfile} into this one.
   *
   * <p>We will only retain the shared tags and share metadata. The timestamps are copied over from
   * this dataset. It is the responsibility of the user to ensure that the two datasets are matched
   * on important grouping information
   *
   * @param other a {@link DatasetProfile}
   * @return a merged {@link DatasetProfile} with summed up columns
   */
  public DatasetProfile merge(@NonNull DatasetProfile other) {
    val sharedTags = ImmutableMap.<String, String>builder();
    for (val tagKey : this.tags.keySet()) {
      val tagValue = this.tags.get(tagKey);
      if (tagValue.equals(other.tags.get(tagKey))) {
        sharedTags.put(tagKey, tagValue);
      }
    }

    return doMerge(other, sharedTags.build());
  }

  private DatasetProfile doMerge(@NonNull DatasetProfile other, Map<String, String> tags) {
    this.validate();
    other.validate();

    val result =
        new DatasetProfile(
            this.sessionId,
            this.sessionTimestamp,
            this.dataTimestamp,
            tags,
            Collections.emptyMap());

    val sharedMetadata = ImmutableMap.<String, String>builder();
    for (val mKey : this.metadata.keySet()) {
      val mValue = this.metadata.get(mKey);
      if (mValue.equals(other.metadata.get(mKey))) {
        sharedMetadata.put(mKey, mValue);
      }
    }
    result.withAllMetadata(sharedMetadata.build());

    val unionColumns = Sets.union(this.columns.keySet(), other.columns.keySet());
    for (val column : unionColumns) {
      val emptyColumn = new ColumnProfile(column);
      val thisColumn = this.columns.getOrDefault(column, emptyColumn);
      val otherColumn = other.columns.getOrDefault(column, emptyColumn);

      result.columns.put(column, thisColumn.merge(otherColumn));
    }

    if (this.modelProfile != null) {
      result.modelProfile = this.modelProfile.merge(other.modelProfile);
    } else if (other.modelProfile != null) {
      result.modelProfile = other.modelProfile.copy();
    }

    return result;
  }

  public DatasetProfileMessage.Builder toProtobuf() {
    validate();
    val properties = toDatasetProperties();

    val builder = DatasetProfileMessage.newBuilder().setProperties(properties);

    columns.forEach((k, v) -> builder.putColumns(k, v.toProtobuf().build()));
    if (modelProfile != null) {
      builder.setModeProfile(modelProfile.toProtobuf());
    }

    return builder;
  }

  public void writeTo(OutputStream out) throws IOException {
    this.toProtobuf().build().writeDelimitedTo(out);
  }

  public byte[] toBytes() throws IOException {
    val msg = this.toProtobuf().build();
    val bos = new ByteArrayOutputStream(msg.getSerializedSize());
    msg.writeDelimitedTo(bos);
    return bos.toByteArray();
  }

  private DatasetProperties.Builder toDatasetProperties() {
    val dataTimeInMillis = (dataTimestamp == null) ? -1L : dataTimestamp.toEpochMilli();
    return DatasetProperties.newBuilder()
        .setSessionId(sessionId)
        .setSessionTimestamp(sessionTimestamp.toEpochMilli())
        .setDataTimestamp(dataTimeInMillis)
        .putAllTags(tags)
        .putAllMetadata(metadata)
        .setSchemaMajorVersion(SchemaInformation.SCHEMA_MAJOR_VERSION)
        .setSchemaMinorVersion(SchemaInformation.SCHEMA_MINOR_VERSION);
  }

  public static DatasetProfile fromProtobuf(DatasetProfileMessage message) {
    val props = message.getProperties();
    SchemaInformation.validateSchema(props.getSchemaMajorVersion(), props.getSchemaMinorVersion());

    val tags = props.getTagsMap();
    val sessionTimestamp = Instant.ofEpochMilli(props.getSessionTimestamp());
    val dataTimestamp =
        (props.getDataTimestamp() < 0L) ? null : Instant.ofEpochMilli(props.getDataTimestamp());
    val ds =
        new DatasetProfile(
            props.getSessionId(), sessionTimestamp, dataTimestamp, tags, Collections.emptyMap());
    ds.withAllMetadata(props.getMetadataMap());
    message.getColumnsMap().forEach((k, v) -> ds.columns.put(k, ColumnProfile.fromProtobuf(v)));

    ds.modelProfile = ModelProfile.fromProtobuf(message.getModeProfile());

    ds.validate();

    return ds;
  }

  public static DatasetProfile parse(InputStream in) throws IOException {
    val msg = DatasetProfileMessage.parseDelimitedFrom(in);
    return DatasetProfile.fromProtobuf(msg);
  }

  private void writeObject(ObjectOutputStream out) throws IOException {
    validate();

    this.writeTo(out);
  }

  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    val copy = DatasetProfile.parse(in);

    this.sessionId = copy.sessionId;
    this.sessionTimestamp = copy.sessionTimestamp;
    this.dataTimestamp = copy.dataTimestamp;
    this.metadata = copy.metadata;
    this.tags = copy.tags;
    this.columns = copy.columns;
    this.modelProfile = copy.modelProfile;

    this.validate();
  }

  @Value
  static class Pair {
    String name;
    ColumnSummary statistics;

    static Pair fromColumn(ColumnProfile column) {
      return new Pair(column.getColumnName(), column.toColumnSummary());
    }
  }
}
