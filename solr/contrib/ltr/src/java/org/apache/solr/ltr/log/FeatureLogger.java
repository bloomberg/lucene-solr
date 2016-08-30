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
package org.apache.solr.ltr.log;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.solr.ltr.ranking.ModelQuery;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.search.SolrCache;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FeatureLogger can be registered in a model and provide a strategy for logging
 * the feature values.
 */
public abstract class FeatureLogger<FV_TYPE> {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected enum FeatureFormat {DENSE, SPARSE};
  protected final FeatureFormat featureFormat;
  
  protected FeatureLogger(FeatureFormat f) {
    this.featureFormat = f;
  }
  
  /**
   * Log will be called every time that the model generates the feature values
   * for a document and a query.
   *
   * @param docid
   *          Solr document id whose features we are saving
   * @param featureNames
   *          List of all the feature names we are logging
   * @param featureValues
   *          Parallel list to featureNames that stores all the unnormalized
   *          feature values
   * @param featuresUsed
   *          Bitset indicating which featureValues to log
   *
   * @return true if the logger successfully logged the features, false
   *         otherwise.
   */

  public boolean log(int docid, ModelQuery modelQuery,
      SolrIndexSearcher searcher, String[] featureNames, float[] featureValues,
      boolean[] featuresUsed) {
    final FV_TYPE featureVector = makeFeatureVector(featureNames, featureValues,
        featuresUsed);
    if (featureVector == null) {
      return false;
    }
    // FIXME: Confirm this hashing works
    return searcher.cacheInsert(CommonLTRParams.QUERY_FV_CACHE_NAME,
        modelQuery.hashCode() + (31 * docid),featureVector) != null;
  }

  /**
   * returns a FeatureLogger that logs the features in output, using the format
   * specified in the 'stringFormat' param: 'csv' will log the features as a unique
   * string in csv format 'json' will log the features in a map in a Map of
   * featureName keys to featureValue values if format is null or empty, csv
   * format will be selected.
   * 'featureFormat' param: 'dense' will write features in dense format,
   * 'sparse' will write the features in sparse format, null or empty will
   * default to 'sparse'  
   *
   *
   * @return a feature logger for the format specified.
   */
  public static FeatureLogger<?> getFeatureLogger(String stringFormat, String featureFormat) {
    final FeatureFormat f;
    if (featureFormat == null || featureFormat.isEmpty() ||
        featureFormat.equals("sparse")) {
      f = FeatureFormat.SPARSE;
    }
    else if (featureFormat.equals("dense")) {
      f = FeatureFormat.DENSE;
    }
    else {
      f = FeatureFormat.SPARSE;
      log.warn("unknown feature logger feature format {} | {}", stringFormat, featureFormat);
    }
    if ((stringFormat == null) || stringFormat.isEmpty()) {
      return new CSVFeatureLogger(f);
    }
    if (stringFormat.equals("csv")) {
      return new CSVFeatureLogger(f);
    }
    if (stringFormat.equals("json")) {
      return new MapFeatureLogger(f);
    }
    log.warn("unknown feature logger string format {} | {}", stringFormat, featureFormat);
    return null;

  }

  public abstract FV_TYPE makeFeatureVector(String[] featureNames,
      float[] featureValues, boolean[] featuresUsed);

  /**
   * populate the document with its feature vector
   *
   * @param docid
   *          Solr document id
   * @return String representation of the list of features calculated for docid
   */
  
  public FV_TYPE getFeatureVector(int docid, ModelQuery reRankModel,
      SolrIndexSearcher searcher) {
    final SolrCache fvCache = searcher
        .getCache(CommonLTRParams.QUERY_FV_CACHE_NAME);
    return fvCache == null ? null : (FV_TYPE) fvCache.get(reRankModel
        .hashCode() + (31 * docid));
  }

  public static class MapFeatureLogger extends FeatureLogger<Map<String,Float>> {

    public MapFeatureLogger(FeatureFormat f) {
      super(f);
    }
    
    @Override
    public Map<String,Float> makeFeatureVector(String[] featureNames,
        float[] featureValues, boolean[] featuresUsed) {
      boolean isDense = featureFormat.equals(FeatureFormat.DENSE);
      Map<String,Float> hashmap = Collections.emptyMap();
      if (featureNames.length > 0) {
        hashmap = new HashMap<String,Float>(featureValues.length);
        for (int i = 0; i < featuresUsed.length; i++) {
          if (featuresUsed[i] || isDense) {
            hashmap.put(featureNames[i], featureValues[i]);
          }
        }
      }
      return hashmap;
    }

  }

  public static class CSVFeatureLogger extends FeatureLogger<String> {
    StringBuilder sb = new StringBuilder(500);
    char keyValueSep = ':';
    char featureSep = ';';
    
    public CSVFeatureLogger(FeatureFormat f) {
      super(f);
    }

    public CSVFeatureLogger setKeyValueSep(char keyValueSep) {
      this.keyValueSep = keyValueSep;
      return this;
    }

    public CSVFeatureLogger setFeatureSep(char featureSep) {
      this.featureSep = featureSep;
      return this;
    }

    @Override
    public String makeFeatureVector(String[] featureNames,
        float[] featureValues, boolean[] featuresUsed) {
      boolean isDense = featureFormat.equals(FeatureFormat.DENSE);
      for (int i = 0; i < featuresUsed.length; i++) {
        if (featuresUsed[i] || isDense) {
          sb.append(featureNames[i]).append(keyValueSep)
          .append(featureValues[i]);
          sb.append(featureSep);
        }
      }

      final String features = (sb.length() > 0 ? sb.substring(0,
          sb.length() - 1) : "");
      sb.setLength(0);

      return features;
    }

  }

}
