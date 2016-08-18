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
package org.apache.solr.ltr.ranking;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.request.SolrQueryRequest;

/**
 * A delegating feature
 */
public class FilterFeature extends Feature {

  protected final Feature in;
  protected final Normalizer norm;


  /**
   * @param name
   *          Name of the feature
   * @param params
   *          Custom parameters that the feature may use
   * @param id
   *          Unique ID for this feature. Similar to feature name, except it can
   *          be used to directly access the feature in the global list of
   *          features.
   */
  @Override
  public void init(String name, Map<String,Object> params, int id)
      throws FeatureException {
    super.init(name, params, id);
    throw new FeatureException(getClass().getCanonicalName()
        + " init is not supported ("+this+")");
  }

  public FilterFeature(Feature in, Normalizer norm) {
    super();
    this.in = in;
    this.norm = norm;
  }

  @Override
  public String toString(String field) {
    return in.toString(field);
  }

  @Override
  public FeatureWeight createWeight(IndexSearcher searcher,
      boolean needsScores, SolrQueryRequest request, Query originalQuery, Map<String,String> efi) throws IOException {
    final FeatureWeight featureWeight =
        in.createWeight(searcher, needsScores, request, originalQuery, efi);
    featureWeight.setNorm(norm);
    return featureWeight;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = in.hashCode();
    result = (prime * result) + norm.hashCode();
    return result;
  }

  @Override
  public boolean equals(Object o) {
    return sameClassAs(o) &&  equalsTo(getClass().cast(o));
  }

  private boolean equalsTo(FilterFeature other) {
    return
        in.equals(other.in) &&
        norm.equals(other.norm);
  }

  /**
   * @return the name
   */
  @Override
  public String getName() {
    return in.getName();
  }

  /**
   * @return the norm
   */
  @Override
  public Normalizer getNorm() {
    return norm;
  }

  /**
   * @return the id
   */
  @Override
  public int getId() {
    return in.getId();
  }

  protected LinkedHashMap<String,Object> paramsToMap() {
    return in.paramsToMap();
  }

  public LinkedHashMap<String,Object> toMap(String storeName) {
    return in.toMap(storeName);
  }
  
  
  
}
