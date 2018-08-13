package com.linkedin.thirdeye.detection.algorithm;

import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.linkedin.thirdeye.dataframe.DataFrame;
import com.linkedin.thirdeye.dataframe.util.MetricSlice;
import com.linkedin.thirdeye.datalayer.dto.DetectionConfigDTO;
import com.linkedin.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import com.linkedin.thirdeye.detection.ConfigUtils;
import com.linkedin.thirdeye.detection.DataProvider;
import com.linkedin.thirdeye.detection.DetectionPipeline;
import com.linkedin.thirdeye.detection.DetectionPipelineResult;
import com.linkedin.thirdeye.rootcause.impl.MetricEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.collections.MapUtils;

import static com.linkedin.thirdeye.dataframe.util.DataFrameUtils.*;


/**
 * Detection pipeline for dimension exploration with a configurable nested detection pipeline.
 * Loads and prunes a metric's dimensions and sequentially retrieves data to run detection on
 * each filtered time series.
 */
public class DimensionWrapper extends DetectionPipeline {

  // exploration
  private static final String PROP_METRIC_URN = "metricUrn";

  private static final String PROP_DIMENSIONS = "dimensions";

  private static final String PROP_MIN_VALUE = "minValue";
  private static final double PROP_MIN_VALUE_DEFAULT = Double.NaN;

  private static final String PROP_MIN_CONTRIBUTION = "minContribution";
  private static final double PROP_MIN_CONTRIBUTION_DEFAULT = Double.NaN;

  private static final String PROP_K = "k";
  private static final int PROP_K_DEFAULT = -1;

  private static final String PROP_LOOKBACK = "lookback";
  private static final long PROP_LOOKBACK_DEFAULT = TimeUnit.DAYS.toMillis(7);

  // prototyping
  private static final String PROP_NESTED = "nested";

  private static final String PROP_NESTED_METRIC_URN_KEY = "nestedMetricUrnKey";
  private static final String PROP_NESTED_METRIC_URN_KEY_DEFAULT = "metricUrn";

  private static final String PROP_NESTED_METRIC_URNS = "nestedMetricUrns";

  private static final String PROP_CLASS_NAME = "className";

  private final String metricUrn;
  protected final List<String> dimensions;
  private final int k;
  private final double minValue;
  private final double minContribution;
  private final long lookback;

  private final Collection<String> nestedMetricUrns;
  protected final String nestedMetricUrnKey;
  private final List<Map<String, Object>> nestedProperties;

  public DimensionWrapper(DataProvider provider, DetectionConfigDTO config, long startTime, long endTime) {
    super(provider, config, startTime, endTime);

    // exploration
    this.metricUrn = MapUtils.getString(config.getProperties(), PROP_METRIC_URN, null);
    this.minValue = MapUtils.getDoubleValue(config.getProperties(), PROP_MIN_VALUE, PROP_MIN_VALUE_DEFAULT);
    this.minContribution = MapUtils.getDoubleValue(config.getProperties(), PROP_MIN_CONTRIBUTION, PROP_MIN_CONTRIBUTION_DEFAULT);
    this.k = MapUtils.getIntValue(config.getProperties(), PROP_K, PROP_K_DEFAULT);
    this.dimensions = ConfigUtils.getList(config.getProperties().get(PROP_DIMENSIONS));
    this.lookback = MapUtils.getLongValue(config.getProperties(), PROP_LOOKBACK, PROP_LOOKBACK_DEFAULT);

    // prototyping
    Preconditions.checkArgument(config.getProperties().containsKey(PROP_NESTED), "Missing " + PROP_NESTED);

    this.nestedMetricUrns = (Collection<String>) MapUtils.getObject(config.getProperties(), PROP_NESTED_METRIC_URNS, Collections.singleton(this.metricUrn));
    this.nestedMetricUrnKey = MapUtils.getString(config.getProperties(), PROP_NESTED_METRIC_URN_KEY, PROP_NESTED_METRIC_URN_KEY_DEFAULT);
    this.nestedProperties = ConfigUtils.getList(config.getProperties().get(PROP_NESTED));
  }

  @Override
  public DetectionPipelineResult run() throws Exception {
    List<MetricEntity> nestedMetrics = new ArrayList<>();

    if (this.metricUrn != null) {
      // metric and dimension exploration

      long startTime = this.startTime;
      if (this.endTime - this.startTime < this.lookback) {
        startTime = this.endTime - this.lookback;
      }

      MetricEntity metric = MetricEntity.fromURN(this.metricUrn);
      MetricSlice slice = MetricSlice.from(metric.getId(), startTime, this.endTime, metric.getFilters());

      DataFrame aggregates = this.provider.fetchAggregates(Collections.singletonList(slice), this.dimensions).get(slice);

      if (aggregates.isEmpty()) {
        return new DetectionPipelineResult(Collections.<MergedAnomalyResultDTO>emptyList());
      }

      final double total = aggregates.getDoubles(COL_VALUE).sum().fillNull().doubleValue();

      // min value
      if (!Double.isNaN(this.minValue)) {
        aggregates = aggregates.filter(aggregates.getDoubles(COL_VALUE).gte(this.minValue)).dropNull();
      }

      // min contribution
      if (!Double.isNaN(this.minContribution)) {
        aggregates = aggregates.filter(aggregates.getDoubles(COL_VALUE).divide(total).gte(this.minContribution)).dropNull();
      }

      // top k
      if (this.k > 0) {
        aggregates = aggregates.sortedBy(COL_VALUE).tail(this.k).reverse();
      }

      for (String nestedMetricUrn : this.nestedMetricUrns) {
        for (int i = 0; i < aggregates.size(); i++) {
          Multimap<String, String> nestedFilter = ArrayListMultimap.create(metric.getFilters());

          for (String dimName : this.dimensions) {
            nestedFilter.removeAll(dimName); // clear any filters for explored dimension
            nestedFilter.put(dimName, aggregates.getString(dimName, i));
          }

          nestedMetrics.add(MetricEntity.fromURN(nestedMetricUrn).withFilters(nestedFilter));
        }
      }

    } else {
      // metric exploration only

      for (String nestedMetricUrn : this.nestedMetricUrns) {
        nestedMetrics.add(MetricEntity.fromURN(nestedMetricUrn));
      }
    }

    List<MergedAnomalyResultDTO> anomalies = new ArrayList<>();
    Map<String, Object> diagnostics = new HashMap<>();

    for (MetricEntity metric : nestedMetrics) {
      for (Map<String, Object> properties : this.nestedProperties) {
        DetectionPipelineResult intermediate = this.runNested(metric, properties);

        anomalies.addAll(intermediate.getAnomalies());
        diagnostics.put(metric.getUrn(), intermediate.getDiagnostics());
      }
    }

    return new DetectionPipelineResult(anomalies)
        .setDiagnostics(diagnostics);
  }

  protected DetectionPipelineResult runNested(MetricEntity metric, Map<String, Object> template) throws Exception {
    Preconditions.checkArgument(template.containsKey(PROP_CLASS_NAME), "Nested missing " + PROP_CLASS_NAME);

    Map<String, Object> properties = new HashMap<>(template);

    properties.put(this.nestedMetricUrnKey, metric.getUrn());

    DetectionConfigDTO nestedConfig = new DetectionConfigDTO();
    nestedConfig.setId(this.config.getId());
    nestedConfig.setName(this.config.getName());
    nestedConfig.setProperties(properties);

    DetectionPipeline pipeline = this.provider.loadPipeline(nestedConfig, this.startTime, this.endTime);

    return pipeline.run();
  }
}
