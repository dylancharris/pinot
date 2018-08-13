package com.linkedin.thirdeye.constant;

public enum MetricAggFunction {
  SUM, AVG, COUNT, MAX,
  percentileTDigest5, percentileTDigest10, percentileTDigest20, percentileTDigest25, percentileTDigest30,
  percentileTDigest40, percentileTDigest50, percentileTDigest60, percentileTDigest70, percentileTDigest75,
  percentileTDigest80, percentileTDigest90, percentileTDigest95, percentileTDigest99;

  public boolean isTDigest() {
    return this.toString().startsWith("percentileTDigest");
  }
}
