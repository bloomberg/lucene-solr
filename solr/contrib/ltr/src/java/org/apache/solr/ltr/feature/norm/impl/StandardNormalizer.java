/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.ltr.feature.norm.impl;

import java.util.LinkedHashMap;

import org.apache.solr.ltr.feature.norm.Normalizer;

public class StandardNormalizer extends Normalizer {

  private float avg = 0f;
  private float std = 1f;

  public float getAvg() {
    return avg;
  }

  public void setAvg(float avg) {
    this.avg = avg;
  }

  public float getStd() {
    return std;
  }

  public void setStd(float std) {
    this.std = std;
  }

  public void setAvg(String avg) {
    this.avg = Float.parseFloat(avg);
  }

  public void setStd(String std) {
    this.std = Float.parseFloat(std);
  }

  @Override
  public float normalize(float value) {
    return (value - avg) / std;
  }

  @Override
  public LinkedHashMap<String,Object> paramsToMap() {
    final LinkedHashMap<String,Object> params = new LinkedHashMap<>(2, 1.0f);
    params.put("avg", avg);
    params.put("std", std);
    return params;
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder(64); // default initialCapacity of 16 won't be enough
    sb.append(getClass().getSimpleName()).append('(');
    sb.append("avg=").append(avg);
    sb.append(",std=").append(avg).append(')');
    return sb.toString();
  }

}
