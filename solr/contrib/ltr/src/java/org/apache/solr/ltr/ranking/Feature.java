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

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.feature.norm.impl.IdentityNormalizer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.ltr.util.NamedParams;
import org.apache.solr.request.SolrQueryRequest;

/**
 * A 'recipe' for computing a feature
 */
public abstract class Feature extends Query implements Cloneable {

  protected String name;
  protected String type;
  protected Normalizer norm = IdentityNormalizer.INSTANCE;
  protected int id;
  protected NamedParams params = NamedParams.EMPTY;

  public void init(String name, NamedParams params, int id)
      throws FeatureException {
    this.name = name;
    this.type = getClass().getSimpleName();
    this.params = params;
    this.id = id;
  }

  public Feature() {

  }

  /** Returns a clone of this feature query. */
  @Override
  public Query clone() {

    try {
      return (Query) super.clone();
    } catch (final CloneNotSupportedException e) {
      // FIXME throw the exception, wrap into another exception?
      e.printStackTrace();
    }
    return null;
  }

  @Override
  public String toString(String field) {
    return "Feature [name=" + name + ", type=" + type + ", id=" + id
        + ", params=" + params + "]";
  }

  public abstract FeatureWeight createWeight(IndexSearcher searcher,
      boolean needsScores, SolrQueryRequest request, Query originalQuery, Map<String,String> efi) throws IOException;

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = classHash();
    result = (prime * result) + id;
    result = (prime * result) + ((name == null) ? 0 : name.hashCode());
    result = (prime * result) + ((params == null) ? 0 : params.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object o) {
    return sameClassAs(o) &&  equalsTo(getClass().cast(o));
  }

  private boolean equalsTo(Feature other) {
    if (id != other.id) {
        return false;
    }
    if (name == null) {
        if (other.name != null) {
            return false;
        }
    } else if (!name.equals(other.name)) {
        return false;
    }
    if (params == null) {
        if (other.params != null) {
            return false;
        }
    } else if (!params.equals(other.params)) {
        return false;
    }
    return true;
  }

  /**
   * @return the name
   */
  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  /**
   * @return the norm
   */
  public Normalizer getNorm() {
    return norm;
  }

  /**
   * @return the id
   */
  public int getId() {
    return id;
  }

  /**
   * @return the params
   */
  public NamedParams getParams() {
    return params;
  }

  public void setNorm(Normalizer norm) {
    this.norm = norm;

  }

}
