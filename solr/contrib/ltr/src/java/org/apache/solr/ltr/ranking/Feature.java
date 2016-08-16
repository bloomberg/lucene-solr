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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.solr.ltr.feature.norm.Normalizer;
import org.apache.solr.ltr.feature.norm.impl.IdentityNormalizer;
import org.apache.solr.ltr.util.FeatureException;
import org.apache.solr.ltr.util.MacroExpander;
import org.apache.solr.ltr.util.NamedParams;
import org.apache.solr.request.SolrQueryRequest;

/**
 * A 'recipe' for computing a feature
 */
public abstract class Feature extends Query {

  protected String name;
  protected int id;

  @Deprecated
  private NamedParams params = NamedParams.EMPTY;


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
  public void init(String name, NamedParams params, int id)
      throws FeatureException {
    this.name = name;
    this.params = params;
    this.id = id;
  }

  public Feature() {
  }

  @Override
  public String toString(String field) {
    final StringBuilder sb = new StringBuilder(64); // default initialCapacity of 16 won't be enough
    sb.append(getClass().getSimpleName());
    sb.append(" [name=").append(name);
    final LinkedHashMap<String,Object> params = paramsToMap();
    if (params != null) {
      sb.append(", params=").append(params);
    }
    sb.append(']');
    return sb.toString();
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

  /**
   * @return the norm
   */
  public Normalizer getNorm() {
    return IdentityNormalizer.INSTANCE;
  }

  /**
   * @return the id
   */
  public int getId() {
    return id;
  }

  protected abstract LinkedHashMap<String,Object> paramsToMap();

  public LinkedHashMap<String,Object> toMap(String storeName) {
    final LinkedHashMap<String,Object> o = new LinkedHashMap<>(4, 1.0f);
    o.put("name", name);
    o.put("type", getClass().getCanonicalName());
    o.put("store", storeName);
    o.put("params", paramsToMap());
    return o;
  }
  
  
  
  public abstract class FeatureWeight extends Weight {

    final protected IndexSearcher searcher;
    final protected SolrQueryRequest request;
    final protected Map<String,String> efi;
    final protected MacroExpander macroExpander;
    final protected Query originalQuery;

    private Normalizer norm;

    /**
     * Initialize a feature without the normalizer from the feature file. This is
     * called on initial construction since multiple models share the same
     * features, but have different normalizers. A concrete model's feature is
     * copied through featForNewModel().
     *
     * @param q
     *          Solr query associated with this FeatureWeight
     * @param searcher
     *          Solr searcher available for features if they need them
     */
    public FeatureWeight(Query q, IndexSearcher searcher, 
        SolrQueryRequest request, Query originalQuery, Map<String,String> efi) {
      super(q);
      this.searcher = searcher;
      this.request = request;
      this.originalQuery = originalQuery;
      this.efi = efi;
      macroExpander = new MacroExpander(efi);
    }

    public String getName() {
      return Feature.this.name;
    }

    public void setNorm(Normalizer norm) {
      this.norm = norm;
    }

    public Normalizer getNorm() {
      if (norm != null) {
        return norm;
      } else {
        return Feature.this.getNorm();
      }
    }

    public int getId() {
      return Feature.this.id;
    }

    public float getDefaultValue() {
      return 0;
    }

    @Override
    public abstract FeatureScorer scorer(LeafReaderContext context)
        throws IOException;

    @Override
    public Explanation explain(LeafReaderContext context, int doc)
        throws IOException {
      final FeatureScorer r = scorer(context);
      float score = getDefaultValue();
      if (r != null) {
        r.iterator().advance(doc);
        if (r.docID() == doc) score = r.score();
        return Explanation.match(score, toString());
      }else{
        return Explanation.match(score, "The feature has no value");
      }
    }

    /**
     * Used in the FeatureWeight's explain. Each feature should implement this
     * returning properties of the specific scorer useful for an explain. For
     * example "MyCustomClassFeature [name=" + name + "myVariable:" + myVariable +
     * "]";  If not provided, a default implementation will return basic feature 
     * properties, which might not include query time specific values.
     */
    @Override
    public String toString() {
      return Feature.this.toString();
    }
    
    
    @Override
    public void extractTerms(Set<Term> terms) {
      // needs to be implemented by query subclasses
      throw new UnsupportedOperationException();
    }

    
    
    /**
     * A 'recipe' for computing a feature
     */
    public abstract class FeatureScorer extends Scorer {

      protected String name;
      private HashMap<String,Object> docInfo;

      public FeatureScorer(Feature.FeatureWeight weight) {
        super(weight);
        name = weight.getName();
      }

      @Override
      public abstract float score() throws IOException;


      /**
       * Used to provide context from initial score steps to later reranking steps.
       */
      public void setDocInfo(HashMap<String,Object> iDocInfo) {
        docInfo = iDocInfo;
      }

      public Object getDocParam(String key) {
        return docInfo.get(key);
      }

      public boolean hasDocParam(String key) {
        if (docInfo != null) {
          return docInfo.containsKey(key);
        } else {
          return false;
        }
      }

      @Override
      public int freq() throws IOException {
        throw new UnsupportedOperationException();
      }
    }
    
  }

}
