package com.linkedin.thirdeye.detection.algorithm;

import com.linkedin.thirdeye.dataframe.DataFrame;
import com.linkedin.thirdeye.dataframe.util.MetricSlice;
import com.linkedin.thirdeye.datalayer.dto.DetectionConfigDTO;
import com.linkedin.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import com.linkedin.thirdeye.detection.DataProvider;
import com.linkedin.thirdeye.rootcause.impl.MetricEntity;
import java.util.Collections;
import java.util.Map;
import org.apache.commons.collections.MapUtils;

import static com.linkedin.thirdeye.dataframe.util.DataFrameUtils.*;


/**
 * This filter wrapper filters the anomalies if either the min or max thresholds do not pass.
 */
public class ThresholdRuleFilterWrapper extends RuleBasedFilterWrapper {
  private static final String PROP_MIN = "min";
  private static final double PROP_MIN_DEFAULT = Double.NaN;

  private static final String PROP_MAX = "max";
  private static final double PROP_MAX_DEFAULT = Double.NaN;

  private final double min;
  private final double max;

  public ThresholdRuleFilterWrapper(DataProvider provider, DetectionConfigDTO config, long startTime, long endTime) {
    super(provider, config, startTime, endTime);
    this.min = MapUtils.getDoubleValue(config.getProperties(), PROP_MIN, PROP_MIN_DEFAULT);
    this.max = MapUtils.getDoubleValue(config.getProperties(), PROP_MAX, PROP_MAX_DEFAULT);
  }

  @Override
  boolean isQualified(MergedAnomalyResultDTO anomaly) {
    MetricEntity me = MetricEntity.fromURN(anomaly.getMetricUrn());
    MetricSlice currentSlice = MetricSlice.from(me.getId(), anomaly.getStartTime(), anomaly.getEndTime(), me.getFilters());

    Map<MetricSlice, DataFrame> aggregates = this.provider.fetchAggregates(Collections.singleton(currentSlice), Collections.<String>emptyList());
    double currentValue = getValueFromAggregates(currentSlice, aggregates);
    if (!Double.isNaN(this.min) && currentValue < this.min) {
      return false;
    }
    if (!Double.isNaN(this.max) && currentValue > this.max) {
      return false;
    }
    return true;
  }

  double getValueFromAggregates(MetricSlice slice, Map<MetricSlice, DataFrame> aggregates) {
    return aggregates.get(slice).getDouble(COL_VALUE, 0);
  }
}
