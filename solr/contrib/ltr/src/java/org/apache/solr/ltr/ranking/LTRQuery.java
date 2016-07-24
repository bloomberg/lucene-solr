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
import java.util.Set;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Rescorer;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.handler.component.MergeStrategy;
import org.apache.solr.handler.component.QueryElevationComponent;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.search.QueryCommand;
import org.apache.solr.search.RankQuery;

/**
 * The LTRQuery and LTRWeight wrap the main query to fetch matching docs. It
 * then provides its own TopDocsCollector, which goes through the top X docs and
 * reranks them using the provided reRankModel.
 */
public class LTRQuery extends RankQuery {
  private Query mainQuery = new MatchAllDocsQuery();
  private final LTRRescorer reRankRescorer;
  private final int reRankDocs;
  private Map<BytesRef,Integer> boostedPriority;

  public LTRQuery(int reRankDocs, LTRRescorer reRankRescorer) {
    this.reRankDocs = reRankDocs;
    this.reRankRescorer = reRankRescorer;
  }

  @Override
  public int hashCode() {
    //FIXME this hash function must be double checked
    return (mainQuery.hashCode() + reRankRescorer.hashCode() + reRankDocs);
  }

  @Override
  public boolean equals(Object o) {
    return sameClassAs(o) &&  equalsTo(getClass().cast(o));
  }

  private boolean equalsTo(LTRQuery other) {
    
    return (mainQuery.equals(other.mainQuery)
        && reRankRescorer.equals(other.reRankRescorer) && (reRankDocs == other.reRankDocs));
  }



  @Override
  public RankQuery wrap(Query _mainQuery) {
    if (_mainQuery != null) {
      mainQuery = _mainQuery;
    }
    reRankRescorer.setOriginalQuery(mainQuery);
    return this;
  }

  @Override
  public MergeStrategy getMergeStrategy() {
    return null;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public TopDocsCollector getTopDocsCollector(int len, QueryCommand cmd,
      IndexSearcher searcher) throws IOException {

    if (boostedPriority == null) {
      final SolrRequestInfo info = SolrRequestInfo.getRequestInfo();
      if (info != null) {
        final Map context = info.getReq().getContext();
        boostedPriority = (Map<BytesRef,Integer>) context
            .get(QueryElevationComponent.BOOSTED_PRIORITY);
        // https://github.com/apache/lucene-solr/blob/5775be6e6242c0f7ec108b10ebbf9da3a7d07a4b/lucene/queries/src/java/org/apache/lucene/queries/function/valuesource/TFValueSource.java#L56
        // function query needs the searcher in the context
        context.put("searcher", searcher);
      }
    }

    return new LTRCollector(reRankDocs, reRankRescorer, cmd, searcher,
        boostedPriority);
  }

  @Override
  public String toString(String field) {
    return "{!ltr mainQuery='" + mainQuery.toString() + "' reRankRescorer='"
        + reRankRescorer.toString() + "' reRankDocs=" + reRankDocs + "}";
  }

  @Override
  public Weight createWeight(IndexSearcher searcher, boolean needsScores, float boost)
      throws IOException {
    final Weight mainWeight = mainQuery.createWeight(searcher, needsScores, boost);
    return new LTRWeight(searcher, mainWeight, reRankRescorer);
  }

  /**
   * This is the weight for the main solr query in the LTRQuery. The only thing
   * this really does is have an explain using the reRankQuery.
   */
  public class LTRWeight extends Weight {
    private final Rescorer reRankRescorer;
    private final Weight mainWeight;
    private final IndexSearcher searcher;

    public LTRWeight(IndexSearcher searcher, Weight mainWeight,
        Rescorer reRankRescorer) throws IOException {
      super(LTRQuery.this);
      this.reRankRescorer = reRankRescorer;
      this.mainWeight = mainWeight;
      this.searcher = searcher;
    }

    @Override
    public Explanation explain(LeafReaderContext context, int doc)
        throws IOException {
      final Explanation mainExplain = mainWeight.explain(context, doc);
      return reRankRescorer.explain(searcher, mainExplain,
          context.docBase + doc);
    }

    @Override
    public void extractTerms(Set<Term> terms) {
      mainWeight.extractTerms(terms);
    }

    @Override
    public Scorer scorer(LeafReaderContext context) throws IOException {
      return mainWeight.scorer(context);
    }
  }
}
