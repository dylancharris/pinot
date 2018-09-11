package com.linkedin.thirdeye.detection.algorithm;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.linkedin.thirdeye.constant.AnomalyFeedbackType;
import com.linkedin.thirdeye.dataframe.BooleanSeries;
import com.linkedin.thirdeye.dataframe.DataFrame;
import com.linkedin.thirdeye.dataframe.DoubleSeries;
import com.linkedin.thirdeye.dataframe.LongSeries;
import com.linkedin.thirdeye.dataframe.util.DataFrameUtils;
import com.linkedin.thirdeye.dataframe.util.MetricSlice;
import com.linkedin.thirdeye.datalayer.dto.DetectionConfigDTO;
import com.linkedin.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import com.linkedin.thirdeye.detection.AnomalySlice;
import com.linkedin.thirdeye.detection.ConfigUtils;
import com.linkedin.thirdeye.detection.DataProvider;
import com.linkedin.thirdeye.detection.DetectionPipelineResult;
import com.linkedin.thirdeye.detection.StaticDetectionPipeline;
import com.linkedin.thirdeye.detection.StaticDetectionPipelineData;
import com.linkedin.thirdeye.detection.StaticDetectionPipelineModel;
import com.linkedin.thirdeye.rootcause.impl.MetricEntity;
import com.linkedin.thirdeye.rootcause.timeseries.Baseline;
import com.linkedin.thirdeye.rootcause.timeseries.BaselineAggregate;
import com.linkedin.thirdeye.rootcause.timeseries.BaselineAggregateType;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.apache.commons.collections.MapUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.DurationFieldType;
import org.joda.time.Period;


public class MovingWindowAlgorithm extends StaticDetectionPipeline {
  private static final String COL_CURR = "currentValue";
  private static final String COL_BASE = "baselineValue";
  private static final String COL_STD = "std";
  private static final String COL_MEAN = "mean";
  private static final String COL_QUANTILE_MIN = "quantileMin";
  private static final String COL_QUANTILE_MAX = "quantileMax";
  private static final String COL_ZSCORE = "zscore";
  private static final String COL_KERNEL = "kernel";
  private static final String COL_KERNEL_ZSCORE = "kernelZscore";
  private static final String COL_VIOLATION = "violation";
  private static final String COL_ANOMALY = "anomaly";
  private static final String COL_OUTLIER = "outlier";
  private static final String COL_COMPUTED_OUTLIER = "computedOutlier";
  private static final String COL_TIME = DataFrameUtils.COL_TIME;
  private static final String COL_VALUE = DataFrameUtils.COL_VALUE;
  private static final String COL_COMPUTED_VALUE = "computedValue";
  private static final String COL_WINDOW_SIZE = "windowSize";
  private static final String COL_BASELINE = "baseline";

  private static final String COL_WEIGHT = "weight";
  private static final String COL_WEIGHTED_VALUE = "weightedValue";

  private static final String PROP_METRIC_URN = "metricUrn";

  private final MetricSlice sliceData;
  private final MetricSlice sliceDetection;
  private final AnomalySlice anomalySlice;

  private final Period windowSize;
  private final Period minLookback;
  private final double zscoreMin;
  private final double zscoreMax;
  private final double zscoreOutlier;
  private final double kernelMin;
  private final double kernelMax;
  private final int kernelSize;
  private final double quantileMin;
  private final double quantileMax;
  private final DateTimeZone timezone;
  private final Period changeDuration;
  private final double changeFraction;
  private final int baselineWeeks;

  private final long effectiveStartTime;

  public MovingWindowAlgorithm(DataProvider provider, DetectionConfigDTO config, long startTime, long endTime) {
    super(provider, config, startTime, endTime);

    Preconditions.checkArgument(config.getProperties().containsKey(PROP_METRIC_URN));

    String metricUrn = MapUtils.getString(config.getProperties(), PROP_METRIC_URN);
    MetricEntity me = MetricEntity.fromURN(metricUrn);

    this.quantileMin = MapUtils.getDoubleValue(config.getProperties(), "quantileMin", Double.NaN);
    this.quantileMax = MapUtils.getDoubleValue(config.getProperties(), "quantileMax", Double.NaN);
    this.zscoreMin = MapUtils.getDoubleValue(config.getProperties(), "zscoreMin", Double.NaN);
    this.zscoreMax = MapUtils.getDoubleValue(config.getProperties(), "zscoreMax", Double.NaN);
    this.zscoreOutlier = MapUtils.getDoubleValue(config.getProperties(), "zscoreOutlier", 3);
    this.kernelMin = MapUtils.getDoubleValue(config.getProperties(), "kernelMin", Double.NaN);
    this.kernelMax = MapUtils.getDoubleValue(config.getProperties(), "kernelMax", Double.NaN);
    this.kernelSize = MapUtils.getIntValue(config.getProperties(), "kernelSize", 1);
    this.timezone = DateTimeZone.forID(MapUtils.getString(config.getProperties(), "timezone", "UTC"));
    this.windowSize = ConfigUtils.parsePeriod(MapUtils.getString(config.getProperties(), "windowSize", "1week"));
    this.minLookback = ConfigUtils.parsePeriod(MapUtils.getString(config.getProperties(), "minLookback", "1day"));
    this.changeDuration = ConfigUtils.parsePeriod(MapUtils.getString(config.getProperties(), "changeDuration", "5days"));
    this.changeFraction = MapUtils.getDoubleValue(config.getProperties(), "changeFraction", 0.666);
    this.baselineWeeks = MapUtils.getIntValue(config.getProperties(), "baselineWeeks", 0);

    Preconditions.checkArgument(Double.isNaN(this.quantileMin) || (this.quantileMin >= 0 && this.quantileMin <= 1.0), "quantileMin must be between 0.0 and 1.0");
    Preconditions.checkArgument(Double.isNaN(this.quantileMax) || (this.quantileMax >= 0 && this.quantileMax <= 1.0), "quantileMax must be between 0.0 and 1.0");

    long effectiveStartTime = startTime;
    if (endTime - startTime < this.minLookback.toStandardDuration().getMillis()) {
      effectiveStartTime = endTime - this.minLookback.toStandardDuration().getMillis();
    }
    this.effectiveStartTime = effectiveStartTime;

    DateTime trainStart = new DateTime(effectiveStartTime, this.timezone).minus(this.windowSize);
    DateTime dataStart = trainStart.minus(new Period().withField(DurationFieldType.weeks(), baselineWeeks));

    this.sliceData = MetricSlice.from(me.getId(), dataStart.getMillis(), endTime, me.getFilters());
    this.sliceDetection = MetricSlice.from(me.getId(), effectiveStartTime, endTime, me.getFilters());

    this.anomalySlice = new AnomalySlice()
        .withConfigId(this.config.getId())
        .withStart(this.sliceData.getStart())
        .withEnd(this.sliceData.getEnd());
  }

  @Override
  public StaticDetectionPipelineModel getModel() {
    StaticDetectionPipelineModel model = new StaticDetectionPipelineModel()
        .withTimeseriesSlices(Collections.singleton(this.sliceData));

    if (this.config.getId() != null) {
      model = model.withAnomalySlices(Collections.singleton(this.anomalySlice));
    }

    return model;
  }

  @Override
  public DetectionPipelineResult run(StaticDetectionPipelineData data) throws Exception {
    DataFrame dfInput = data.getTimeseries().get(this.sliceData);

    Collection<MergedAnomalyResultDTO> existingAnomalies = data.getAnomalies().get(this.anomalySlice);

    // pre-detection change points
    TreeSet<Long> changePoints = getChangePoints(dfInput, this.effectiveStartTime, existingAnomalies);

    // write-through arrays
    dfInput.addSeries(COL_BASELINE, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_MEAN, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_STD, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_ZSCORE, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_QUANTILE_MIN, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_QUANTILE_MAX, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_KERNEL, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_KERNEL_ZSCORE, DoubleSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_WINDOW_SIZE, LongSeries.nulls(dfInput.size()));
    dfInput.addSeries(COL_COMPUTED_VALUE, DoubleSeries.nulls(dfInput.size()));

    // populate pre-existing anomalies
    dfInput = applyExistingAnomalies(dfInput, existingAnomalies);
    dfInput.addSeries(COL_OUTLIER, dfInput.get(COL_ANOMALY).copy());

    // populate pre-computed values
    long[] sTimestamp = dfInput.getLongs(COL_TIME).values();
    double[] sComputed = dfInput.getDoubles(COL_COMPUTED_VALUE).values();
    for (int i = 0; i < sTimestamp.length && sTimestamp[i] < this.effectiveStartTime; i++) {
      double baseline = 0;
      if (this.baselineWeeks > 0) {
        baseline = this.makeBaseline(dfInput, sTimestamp[i], changePoints);
      }
      sComputed[i] = dfInput.getDouble(COL_VALUE, i) - baseline;
    }

    // estimate pre-computed outliers (non-anomaly outliers)
    // NOTE: https://en.m.wikipedia.org/wiki/Median_absolute_deviation
    DataFrame dfPrefix = dfInput.filter(dfInput.getLongs(COL_TIME).lt(this.effectiveStartTime)).dropNull(COL_TIME, COL_COMPUTED_VALUE);
    DoubleSeries prefix = dfPrefix.getDoubles(COL_COMPUTED_VALUE);
    DoubleSeries mad = prefix.subtract(prefix.median()).abs().median();
    if (!mad.isNull(0) && mad.getDouble(0) > 0.0) {
      double std = 1.4826 * mad.doubleValue();
      double mean = AlgorithmUtils.robustMean(prefix, prefix.size()).getDouble(prefix.size() - 1);
      dfPrefix.addSeries(COL_COMPUTED_OUTLIER, prefix.subtract(mean).divide(std).abs().gt(this.zscoreOutlier));

      dfInput.addSeries(dfPrefix, COL_COMPUTED_OUTLIER);
      dfInput.mapInPlace(BooleanSeries.HAS_TRUE, COL_OUTLIER, COL_OUTLIER, COL_COMPUTED_OUTLIER);
    }

    // generate detection time series
    Result result = this.run(dfInput, this.effectiveStartTime, changePoints);

    List<MergedAnomalyResultDTO> anomalies = this.makeAnomalies(this.sliceDetection, result.data, COL_ANOMALY);

    Map<String, Object> diagnostics = new HashMap<>();
    diagnostics.put(DetectionPipelineResult.DIAGNOSTICS_DATA, result.data.dropAllNullColumns());
    diagnostics.put(DetectionPipelineResult.DIAGNOSTICS_CHANGE_POINTS, result.changePoints);

    return new DetectionPipelineResult(anomalies)
        .setDiagnostics(diagnostics);
  }

  /**
   * Run anomaly detection from a given start timestamp
   *
   * @param df raw input data
   * @param start start time stamp
   * @param changePoints set of change points
   * @return detection result
   * @throws Exception
   */
  private Result run(DataFrame df, long start, TreeSet<Long> changePoints) throws Exception {

    // write-through arrays
    double[] sBaseline = df.getDoubles(COL_BASELINE).values();
    double[] sMean = df.getDoubles(COL_MEAN).values();
    double[] sStd = df.getDoubles(COL_STD).values();
    double[] sZscore = df.getDoubles(COL_ZSCORE).values();
    double[] sQuantileMin = df.getDoubles(COL_QUANTILE_MIN).values();
    double[] sQuantileMax = df.getDoubles(COL_QUANTILE_MAX).values();
    double[] sKernel = df.getDoubles(COL_KERNEL).values();
    double[] sKernelZscore = df.getDoubles(COL_KERNEL_ZSCORE).values();
    double[] sComputed = df.getDoubles(COL_COMPUTED_VALUE).values();
    byte[] sAnomaly = df.getBooleans(COL_ANOMALY).values();
    byte[] sOutlier = df.getBooleans(COL_OUTLIER).values();
    long[] sWindowSize = df.getLongs(COL_WINDOW_SIZE).values();

    // scan
    List<Long> timestamps = df.getLongs(COL_TIME).filter(df.getLongs(COL_TIME).between(start, this.endTime)).dropNull().toList();
    for (long timestamp : timestamps) {

      //
      // test for intra-detection change points
      //
      long fractionRangeStart = new DateTime(timestamp, this.timezone).minus(this.changeDuration).getMillis();
      DataFrame changePointWindow = df.filter(df.getLongs(COL_TIME).between(fractionRangeStart, timestamp)).dropNull(COL_TIME);

      Long latestChangePoint = changePoints.floor(timestamp);
      long minChangePoint = latestChangePoint == null ? fractionRangeStart : new DateTime(latestChangePoint, this.timezone).plus(this.changeDuration).getMillis();

      long fractionChangePoint = extractAnomalyFractionChangePoint(changePointWindow, this.changeFraction);

      if (fractionChangePoint >= 0 && fractionChangePoint >= minChangePoint) {
        TreeSet<Long> changePointsNew = new TreeSet<>(changePoints);
        changePointsNew.add(fractionChangePoint);
        System.out.println("change point during execution at " + timestamp + " for " + this.sliceData);

        return this.run(df, timestamp, changePointsNew);
      }

      // source index
      int index = df.getLongs(COL_TIME).find(timestamp);

      //
      // computed values
      //
      double value = df.getDouble(COL_VALUE, index);

      double baseline = 0;
      if (this.baselineWeeks > 0) {
        baseline = this.makeBaseline(df, timestamp, changePoints);
      }
      sBaseline[index] = baseline;

      double computed = value - baseline;
      sComputed[index] = computed;

      final int kernelOffset = -1 * this.kernelSize / 2;
      if (!Double.isNaN(sComputed[index + kernelOffset])) {
        sKernel[index + kernelOffset] = AlgorithmUtils.robustMean(df.getDoubles(COL_COMPUTED_VALUE).slice(index - this.kernelSize + 1, index + 1), this.kernelSize).getDouble(this.kernelSize - 1);
      }

      //
      // variable look back window
      //
      DataFrame window = this.makeWindow(df, timestamp, changePoints);
      sWindowSize[index] = window.size();

      if (window.size() <= 1) {
        continue;
      }

      //
      // derived window scores and metrics
      //
      DoubleSeries computedValues = window.getDoubles(COL_COMPUTED_VALUE);

      double mean = computedValues.mean().doubleValue();
      double std = computedValues.std().doubleValue();
      double zscore = (computed - mean) / std;
      double kernelZscore = (sKernel[index + kernelOffset] - mean) / std;

      sMean[index] = mean;
      sStd[index] = std;
      sZscore[index] = zscore;
      sKernelZscore[index + kernelOffset] = kernelZscore;

      // outlier elimination for future windows
      if (!Double.isNaN(this.zscoreOutlier) && Math.abs(zscore) > this.zscoreOutlier) {
        sOutlier[index] = 1;
      }

      // quantile anomalies
      if (!Double.isNaN(this.quantileMin)) {
        sQuantileMin[index] = computedValues.quantile(this.quantileMin).doubleValue();
        sAnomaly[index] |= (computed < sQuantileMin[index] ? 1 : 0);
      }

      if (!Double.isNaN(this.quantileMax)) {
        sQuantileMax[index] = computedValues.quantile(this.quantileMax).doubleValue();
        sAnomaly[index] |= (computed > sQuantileMax[index] ? 1 : 0);
      }

      // zscore anomalies
      BooleanSeries partialViolation = BooleanSeries.fillValues(df.size(), false);

      if (!Double.isNaN(this.zscoreMin) && zscore < this.zscoreMin) {
        sAnomaly[index] |= 1;
        partialViolation = partialViolation.or(df.getDoubles(COL_ZSCORE).lt(this.zscoreMin / 2));
      }

      if (!Double.isNaN(this.zscoreMax) && zscore > this.zscoreMax) {
        sAnomaly[index] |= 1;
        partialViolation = partialViolation.or(df.getDoubles(COL_ZSCORE).gt(this.zscoreMax / 2));
      }

      // range anomalies (zscore kernel)
      if (!Double.isNaN(this.kernelMin) && kernelZscore < this.kernelMin) {
        sAnomaly[index + kernelOffset] |= 1;
        partialViolation = partialViolation.or(df.getDoubles(COL_KERNEL_ZSCORE).lt(this.kernelMin / 2));
      }

      if (!Double.isNaN(this.kernelMax) && kernelZscore > this.kernelMax) {
        sAnomaly[index + kernelOffset] |= 1;
        partialViolation = partialViolation.or(df.getDoubles(COL_KERNEL_ZSCORE).gt(this.kernelMax / 2));
      }

      // anomaly region expansion
      if (partialViolation.hasTrue()) {
        partialViolation = partialViolation.or(df.getBooleans(COL_ANOMALY));
        sAnomaly = anomalyRangeHelper(df, df.getBooleans(COL_ANOMALY), partialViolation).getBooleans(COL_ANOMALY).values();
      }

      // mark anomalies as outliers
      sOutlier = df.mapInPlace(BooleanSeries.HAS_TRUE, COL_OUTLIER, COL_ANOMALY, COL_OUTLIER).getBooleans(COL_OUTLIER).values();
    }

    return new Result(df, changePoints);
  }

  /**
   * Helper for in-place insertion and expansion of anomaly ranges
   *
   * @param df data frame
   * @param violations boolean series of violations
   * @param partialViolations boolean series of partial violations for expansion
   * @return modified data frame
   */
  static DataFrame anomalyRangeHelper(DataFrame df, BooleanSeries violations, BooleanSeries partialViolations) {
    df.addSeries(COL_VIOLATION, expandViolation(violations, partialViolations).fillNull());
    df.mapInPlace(BooleanSeries.HAS_TRUE, COL_ANOMALY, COL_ANOMALY, COL_VIOLATION);
    df.dropSeries(COL_VIOLATION);
    return df;
  }

  /**
   * Expand violation ranges via the partial-violation threshold.
   *
   * @param violation boolean series of violations
   * @param partialViolation boolean series of partial violations
   * @return boolean series of expanded violations
   */
  static BooleanSeries expandViolation(BooleanSeries violation, BooleanSeries partialViolation) {
    if (violation.size() != partialViolation.size()) {
      throw new IllegalArgumentException("Series must be of equal size");
    }

    // TODO max lookback/forward range for performance

    byte[] full = violation.values();
    byte[] partial = partialViolation.values();
    byte[] output = new byte[full.length];

    int lastPartial = -1;
    for (int i = 0; i < violation.size(); i++) {
      if (lastPartial >= 0 && BooleanSeries.isFalse(partial[i])) {
        lastPartial = -1;
      }

      if (lastPartial < 0 && BooleanSeries.isTrue(partial[i])) {
        lastPartial = i;
      }

      if (full[i] > 0) {
        // partial[i] must be 1 here
        if (partial[i] != 1) {
          System.out.println("meh.");
        }

        int j = lastPartial;

        for (; j < full.length && !BooleanSeries.isFalse(partial[j]); j++) {
          if (!BooleanSeries.isNull(partial[j])) {
            output[j] = 1;
          }
        }

        // move i to last checked candidate
        i = j - 1;
      }
    }

    return BooleanSeries.buildFrom(output);
  }

  /**
   * Find change points within window via anomaly fraction
   *
   * @param window data frame
   * @param fraction anomaly range fraction of total window
   * @return fraction of anomaly period compared to overall period
   */
  static long extractAnomalyFractionChangePoint(DataFrame window, double fraction) {
    long[] timestamp = window.getLongs(COL_TIME).values();
    byte[] anomaly = window.getBooleans(COL_ANOMALY).values();

    int max = window.get(COL_TIME).count();
    int count = 0;
    for (int i = window.size() - 1; i >= 0; i--) {
      if (!LongSeries.isNull(timestamp[i]) && !BooleanSeries.isNull(anomaly[i])) {
        count += BooleanSeries.isTrue(anomaly[i]) ? 1 : 0;
      }

      if (count / (double) max >= fraction) {
        return timestamp[i];
      }
    }

    return -1;
  }

  /**
   * Populates the anomaly series with {@code true} values for a given collection of anomalies.
   *
   * @param df data frame
   * @param anomalies pre-existing anomalies
   * @return anomaly populated data frame
   */
  DataFrame applyExistingAnomalies(DataFrame df, Collection<MergedAnomalyResultDTO> anomalies) {
    DataFrame res = new DataFrame(df)
        .addSeries(COL_ANOMALY, BooleanSeries.fillValues(df.size(), false));

    for (MergedAnomalyResultDTO anomaly : anomalies) {
      if (anomaly.getFeedback() == null ||
          !anomaly.getFeedback().getFeedbackType().equals(AnomalyFeedbackType.NOT_ANOMALY)) {
        res.set(COL_ANOMALY, res.getLongs(COL_TIME).between(anomaly.getStartTime(), anomaly.getEndTime()), BooleanSeries.fillValues(df.size(), true));
      }
    }

    return res;
  }

  /**
   * Returns a dataframe with values differentiated vs a baseline. Prefers values after change-point if available
   *
   * @param df data (COL_TIME, COL_VALUE, COL_OUTLIER)
   * @param baseline baseline config
   * @param baseSlice base metric slice
   * @return data with differentiated values
   */
  static DataFrame diffTimeseries(DataFrame df, Baseline baseline, MetricSlice baseSlice) {
    Collection<MetricSlice> slices = baseline.scatter(baseSlice);

    Map<MetricSlice, DataFrame> map = new HashMap<>();

    for (MetricSlice slice : slices) {
      map.put(slice, sliceTimeseries(df, slice));
    }

    DataFrame dfCurr = new DataFrame(df).renameSeries(COL_VALUE, COL_CURR);
    DataFrame dfBase = baseline.gather(baseSlice, map).renameSeries(COL_VALUE, COL_BASE);
    DataFrame joined = new DataFrame(dfCurr).addSeries(dfBase, COL_BASE);
    joined.addSeries(COL_VALUE, joined.getDoubles(COL_CURR).subtract(joined.get(COL_BASE)));

    return joined;
  }

  /**
   * Returns a merged set of change points computed from time series and user-labeled anomalies.
   *
   * @param df data
   * @return set of change points
   */
  TreeSet<Long> getChangePoints(DataFrame df, long start, Collection<MergedAnomalyResultDTO> anomalies) {
    TreeSet<Long> changePoints = new TreeSet<>();

    // from time series
    if (this.changeDuration.toStandardDuration().getMillis() > 0) {
      // TODO configurable seasonality
      DataFrame dfChangePoint = new DataFrame(df).addSeries(COL_OUTLIER, BooleanSeries.fillValues(df.size(), false));
      Baseline baseline = BaselineAggregate.fromWeekOverWeek(BaselineAggregateType.SUM, 1, 1, this.timezone);
      DataFrame diffSeries = diffTimeseries(dfChangePoint, baseline, this.sliceData).dropNull(COL_TIME, COL_VALUE);

      // less than or equal to start only
      changePoints.addAll(AlgorithmUtils.getChangePointsRobustMean(diffSeries, this.kernelSize, this.changeDuration.toStandardDuration()).headSet(start, true));
    }

    // from anomalies
    Collection<MergedAnomalyResultDTO> changePointAnomalies = Collections2.filter(anomalies,
        new Predicate<MergedAnomalyResultDTO>() {
          @Override
          public boolean apply(MergedAnomalyResultDTO mergedAnomalyResultDTO) {
            return mergedAnomalyResultDTO != null
                && mergedAnomalyResultDTO.getFeedback() != null
                && AnomalyFeedbackType.ANOMALY_NEW_TREND.equals(mergedAnomalyResultDTO.getFeedback().getFeedbackType());
          }
        });

    for (MergedAnomalyResultDTO anomaly : changePointAnomalies) {
      changePoints.add(anomaly.getStartTime());
    }

    return changePoints;
  }

  /**
   * Helper slices base time series for given metric time slice
   *
   * @param df time series dataframe
   * @param slice metric slice
   * @return time series for given slice (range)
   */
  static DataFrame sliceTimeseries(DataFrame df, MetricSlice slice) {
    return df.filter(df.getLongs(COL_TIME).between(slice.getStart(), slice.getEnd())).dropNull(COL_TIME);
  }

  /**
   * Returns variable-size look back window for given timestamp.
   *
   * @param df data
   * @param tCurrent end timestamp (exclusive)
   * @param changePoints change points
   * @return window data frame
   */
  DataFrame makeWindow(DataFrame df, long tCurrent, TreeSet<Long> changePoints) {
    DateTime now = new DateTime(tCurrent);
    long tStart = now.minus(this.windowSize).getMillis();

    // truncate history at change point but leave at least a window equal to changeDuration
    Long changePoint = changePoints.lower(tCurrent);
    if (changePoint != null) {
      tStart = Math.max(tStart, changePoint);
    }

    // use non-outlier period, unless not enough history (anomalies are outliers too)
    BooleanSeries timeFilter = df.getLongs(COL_TIME).between(tStart, tCurrent);
    BooleanSeries outlierAndTimeFilter = df.getBooleans(COL_OUTLIER).not().and(timeFilter);

    // TODO make threshold for fallback to outlier period configurable
    if (outlierAndTimeFilter.sum().fillNull().longValue() <= timeFilter.sum().fillNull().longValue() / 3) {
      return df.filter(timeFilter).dropNull(COL_TIME, COL_COMPUTED_VALUE);
    }

    return df.filter(outlierAndTimeFilter).dropNull(COL_TIME, COL_COMPUTED_VALUE);
  }

  /**
   * Helper generates baseline value for timestamp via exponential smoothing
   *
   * @param df
   * @param tCurrent
   * @param changePoints
   * @return
   */
  double makeBaseline(DataFrame df, long tCurrent, TreeSet<Long> changePoints) {
    DateTime now = new DateTime(tCurrent);

    int index = df.getLongs(COL_TIME).find(tCurrent);
    if (index < 0) {
      return Double.NaN;
    }

    if (this.baselineWeeks <= 0) {
      return 0.0;
    }

    // construct baseline
    DataFrame raw = new DataFrame(COL_TIME, LongSeries.nulls(this.baselineWeeks))
        .addSeries(COL_VALUE, DoubleSeries.nulls(this.baselineWeeks))
        .addSeries(COL_WEIGHT, DoubleSeries.nulls(this.baselineWeeks));

    long[] sTimestamp = raw.getLongs(COL_TIME).values();
    double[] sValue = raw.getDoubles(COL_VALUE).values();
    double[] sWeight = raw.getDoubles(COL_WEIGHT).values();

    // TODO fit actual model
    for (int i = 0; i < this.baselineWeeks; i++) {
      int offset = this.baselineWeeks - i;
      long timestamp = now.minus(new Period().withWeeks(offset)).getMillis();
      sTimestamp[i] = timestamp;

      Long lastChangePoint = changePoints.floor(timestamp);

      int valueIndex = df.getLongs(COL_TIME).find(timestamp);
      if (valueIndex >= 0) {
        sValue[i] = df.getDouble(COL_VALUE, valueIndex);
        sWeight[i] = Math.pow(0.666, offset);

        if (lastChangePoint != null && timestamp < lastChangePoint) {
          sWeight[i] *= 0.1;
        }

        if (BooleanSeries.isTrue(df.getBoolean(COL_OUTLIER, valueIndex))
            && (lastChangePoint == null || timestamp >= new DateTime(lastChangePoint, this.timezone).plus(this.changeDuration).getMillis())) {
          sWeight[i] *= 0.01;
        }
      }
    }

    DataFrame data = raw.dropNull();
    data.addSeries(COL_WEIGHTED_VALUE, data.getDoubles(COL_VALUE).multiply(data.get(COL_WEIGHT)));

    if (data.isEmpty()) {
      return Double.NaN;
    }

    double totalWeight = data.getDoubles(COL_WEIGHT).sum().doubleValue();
    if (totalWeight <= 0) {
      return Double.NaN;
    }

    DoubleSeries computed = data.getDoubles(COL_WEIGHTED_VALUE).sum().divide(totalWeight);

    if (computed.hasNull()) {
      return Double.NaN;
    }

    return computed.doubleValue();
  }

  /**
   * Container class for detection result
   */
  final class Result {
    final DataFrame data;
    final TreeSet<Long> changePoints;

    public Result(DataFrame data, TreeSet<Long> changePoints) {
      this.data = data;
      this.changePoints = changePoints;
    }
  }
}
