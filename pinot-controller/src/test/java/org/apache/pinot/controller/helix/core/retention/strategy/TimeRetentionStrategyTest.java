/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.controller.helix.core.retention.strategy;

import java.util.concurrent.TimeUnit;
import org.apache.pinot.common.metadata.segment.OfflineSegmentZKMetadata;
import org.apache.pinot.common.metadata.segment.SegmentZKMetadata;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;


/**
 * Unit test for time retention.
 */
public class TimeRetentionStrategyTest {

  @Test
  public void testTimeRetention() {
    TimeRetentionStrategy retentionStrategy = new TimeRetentionStrategy(TimeUnit.DAYS, 30L);

    SegmentZKMetadata metadata = new OfflineSegmentZKMetadata();

    // Without setting time unit or end time, should not throw exception
    assertFalse(retentionStrategy.isPurgeable(metadata));
    metadata.setTimeUnit(TimeUnit.DAYS);
    assertFalse(retentionStrategy.isPurgeable(metadata));

    // Set end time to Jan 2nd, 1970 (not purgeable due to bogus timestamp)
    metadata.setEndTime(1L);
    assertFalse(retentionStrategy.isPurgeable(metadata));

    // Set end time to today
    long today = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
    metadata.setEndTime(today);
    assertFalse(retentionStrategy.isPurgeable(metadata));

    // Set end time to two weeks ago
    metadata.setEndTime(today - 14);
    assertFalse(retentionStrategy.isPurgeable(metadata));

    // Set end time to two months ago (purgeable due to being past the retention period)
    metadata.setEndTime(today - 60);
    assertTrue(retentionStrategy.isPurgeable(metadata));

    // Set end time to 200 years in the future (not purgeable due to bogus timestamp)
    metadata.setEndTime(today + (365 * 200));
    assertFalse(retentionStrategy.isPurgeable(metadata));
  }
}
