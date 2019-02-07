/*
 * Copyright (C) 2014-2018 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pinot.thirdeye.detection.components;

import org.apache.pinot.thirdeye.dataframe.DataFrame;
import org.apache.pinot.thirdeye.dataframe.util.MetricSlice;
import org.apache.pinot.thirdeye.datalayer.dto.DatasetConfigDTO;
import org.apache.pinot.thirdeye.datalayer.dto.MergedAnomalyResultDTO;
import org.apache.pinot.thirdeye.datalayer.dto.MetricConfigDTO;
import org.apache.pinot.thirdeye.detection.DataProvider;
import org.apache.pinot.thirdeye.detection.DefaultInputDataFetcher;
import org.apache.pinot.thirdeye.detection.InputDataFetcher;
import org.apache.pinot.thirdeye.detection.MockDataProvider;
import org.apache.pinot.thirdeye.detection.algorithm.AlgorithmUtils;
import org.apache.pinot.thirdeye.detection.spec.PercentageChangeRuleDetectorSpec;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.joda.time.Interval;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.apache.pinot.thirdeye.dataframe.util.DataFrameUtils.*;


public class PercentageChangeRuleDetectorTest {

  private DataProvider provider;
  private DataFrame data;

  @BeforeMethod
  public void beforeMethod() throws Exception {
    try (Reader dataReader = new InputStreamReader(AlgorithmUtils.class.getResourceAsStream("timeseries-4w.csv"))) {
      this.data = DataFrame.fromCsv(dataReader);
      this.data.setIndex(COL_TIME);
      this.data.addSeries(COL_TIME, this.data.getLongs(COL_TIME).multiply(1000));
    }

    MetricConfigDTO metricConfigDTO = new MetricConfigDTO();
    metricConfigDTO.setId(1L);
    metricConfigDTO.setName("thirdeye-test");
    metricConfigDTO.setDataset("thirdeye-test-dataset");

    DatasetConfigDTO datasetConfigDTO = new DatasetConfigDTO();
    datasetConfigDTO.setTimeUnit(TimeUnit.HOURS);
    datasetConfigDTO.setDataset("thirdeye-test-dataset");
    datasetConfigDTO.setTimeDuration(1);

    Map<MetricSlice, DataFrame> timeseries = new HashMap<>();
    timeseries.put(MetricSlice.from(1L, 0L, 604800000L), this.data);
    timeseries.put(MetricSlice.from(1L, 604800000L, 1209600000L), this.data);
    timeseries.put(MetricSlice.from(1L, 1209600000L, 1814400000L), this.data);
    timeseries.put(MetricSlice.from(1L, 1814400000L, 2419200000L), this.data);

    this.provider = new MockDataProvider()
        .setTimeseries(timeseries)
        .setMetrics(Collections.singletonList(metricConfigDTO))
        .setDatasets(Collections.singletonList(datasetConfigDTO));
  }

  @Test
  public void testWeekOverWeekChange() {
    PercentageChangeRuleDetector detector = new PercentageChangeRuleDetector();
    PercentageChangeRuleDetectorSpec spec = new PercentageChangeRuleDetectorSpec();
    spec.setPattern("up");
    spec.setPercentageChange(0.4);
    detector.init(spec, new DefaultInputDataFetcher(this.provider, -1));
    List<MergedAnomalyResultDTO> anomalies = detector.runDetection(new Interval(1814400000L, 2419200000L), "thirdeye:metric:1");
    Assert.assertEquals(anomalies.size(), 2);
    Assert.assertEquals(anomalies.get(0).getStartTime(), 2372400000L);
    Assert.assertEquals(anomalies.get(0).getEndTime(), 2376000000L);
    Assert.assertEquals(anomalies.get(1).getStartTime(), 2379600000L);
    Assert.assertEquals(anomalies.get(1).getEndTime(), 2383200000L);
  }

  @Test
  public void testThreeWeekMedianChange() {
    PercentageChangeRuleDetector detector = new PercentageChangeRuleDetector();
    PercentageChangeRuleDetectorSpec spec = new PercentageChangeRuleDetectorSpec();
    spec.setPercentageChange(0.3);
    spec.setOffset("median3w");
    spec.setPattern("up");
    detector.init(spec, new DefaultInputDataFetcher(this.provider, -1));
    List<MergedAnomalyResultDTO> anomalies = detector.runDetection(new Interval(1814400000L, 2419200000L), "thirdeye:metric:1");
    Assert.assertEquals(anomalies.size(), 4);
    Assert.assertEquals(anomalies.get(0).getStartTime(), 2005200000L);
    Assert.assertEquals(anomalies.get(0).getEndTime(), 2008800000L);
    Assert.assertEquals(anomalies.get(1).getStartTime(), 2134800000L);
    Assert.assertEquals(anomalies.get(1).getEndTime(), 2138400000L);
    Assert.assertEquals(anomalies.get(2).getStartTime(), 2152800000L);
    Assert.assertEquals(anomalies.get(2).getEndTime(), 2156400000L);
    Assert.assertEquals(anomalies.get(3).getStartTime(), 2322000000L);
    Assert.assertEquals(anomalies.get(3).getEndTime(), 2325600000L);
  }

  @Test
  public void testThreeWeekMedianChangeDown() {
    PercentageChangeRuleDetector detector = new PercentageChangeRuleDetector();
    PercentageChangeRuleDetectorSpec spec = new PercentageChangeRuleDetectorSpec();
    spec.setPercentageChange(0.3);
    spec.setOffset("median3w");
    spec.setPattern("down");
    detector.init(spec, new DefaultInputDataFetcher(this.provider, -1));
    List<MergedAnomalyResultDTO> anomalies = detector.runDetection(new Interval(1814400000L, 2419200000L), "thirdeye:metric:1");
    Assert.assertEquals(anomalies.size(), 1);
    Assert.assertEquals(anomalies.get(0).getStartTime(), 2181600000L);
    Assert.assertEquals(anomalies.get(0).getEndTime(), 2185200000L);
  }

  @Test
  public void testThreeWeekMedianChangeUporDown() {
    PercentageChangeRuleDetector detector = new PercentageChangeRuleDetector();
    PercentageChangeRuleDetectorSpec spec = new PercentageChangeRuleDetectorSpec();
    spec.setPercentageChange(0.3);
    spec.setOffset("median3w");
    spec.setPattern("up_or_down");
    detector.init(spec, new DefaultInputDataFetcher(this.provider, -1));
    List<MergedAnomalyResultDTO> anomalies = detector.runDetection(new Interval(1814400000L, 2419200000L), "thirdeye:metric:1");
    Assert.assertEquals(anomalies.size(), 5);
    Assert.assertEquals(anomalies.get(0).getStartTime(), 2005200000L);
    Assert.assertEquals(anomalies.get(0).getEndTime(), 2008800000L);
    Assert.assertEquals(anomalies.get(1).getStartTime(), 2134800000L);
    Assert.assertEquals(anomalies.get(1).getEndTime(), 2138400000L);
    Assert.assertEquals(anomalies.get(2).getStartTime(), 2152800000L);
    Assert.assertEquals(anomalies.get(2).getEndTime(), 2156400000L);
    Assert.assertEquals(anomalies.get(3).getStartTime(), 2181600000L);
    Assert.assertEquals(anomalies.get(3).getEndTime(), 2185200000L);
    Assert.assertEquals(anomalies.get(4).getStartTime(), 2322000000L);
    Assert.assertEquals(anomalies.get(4).getEndTime(), 2325600000L);
  }

}