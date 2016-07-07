package org.apache.solr.ltr.ranking;

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

import java.util.List;

import org.apache.solr.ltr.feature.LTRScoringAlgorithm;
import org.apache.solr.ltr.util.ModelException;
import org.apache.solr.ltr.util.NamedParams;

/**
 * This model introduces the possibility of having composite scoring algorithms.
 */
public abstract class CompositeLTRScoringAlgorithm extends LTRScoringAlgorithm {

  protected LTRScoringAlgorithm scoringAlgorithm;

  public CompositeLTRScoringAlgorithm(String name, List<Feature> features,
                                      String featureStoreName, List<Feature> allFeatures,
                                      NamedParams params) throws ModelException {
    super(name, features, featureStoreName, allFeatures, params);
    this.scoringAlgorithm = createLTRModel(name, features, featureStoreName, allFeatures, params);
  }

  protected abstract LTRScoringAlgorithm createLTRModel(String name, List<Feature> features, String featureStoreName, List<Feature> allFeatures, NamedParams params);

}
