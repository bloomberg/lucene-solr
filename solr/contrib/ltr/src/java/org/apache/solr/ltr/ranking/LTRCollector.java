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
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Rescorer;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopDocsCollector;
import org.apache.lucene.search.TopFieldCollector;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.SolrException;
import org.apache.solr.handler.component.QueryElevationComponent;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.search.QueryCommand;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.hppc.IntFloatHashMap;
import com.carrotsearch.hppc.IntIntHashMap;

@SuppressWarnings("rawtypes")
public class LTRCollector extends TopDocsCollector {
  // FIXME: This should extend ReRankCollector since it is mostly a copy

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  private final Rescorer reRankRescorer;
  private TopDocsCollector mainCollector;
  private final IndexSearcher searcher;
  private final int reRankDocs;
  private final Map<BytesRef,Integer> boostedPriority;

  @SuppressWarnings("unchecked")
  public LTRCollector(int reRankDocs, Rescorer reRankRescorer, QueryCommand cmd,
      IndexSearcher searcher, Map<BytesRef,Integer> boostedPriority)
      throws IOException {
    super(null);
    this.reRankRescorer = reRankRescorer;
    this.reRankDocs = reRankDocs;
    this.boostedPriority = boostedPriority;
    Sort sort = cmd.getSort();
    if (sort == null) {
      mainCollector = TopScoreDocCollector.create(this.reRankDocs);
    } else {
      sort = sort.rewrite(searcher);
      mainCollector = TopFieldCollector.create(sort, this.reRankDocs, false,
          true, true);
    }
    this.searcher = searcher;
  }

  @Override
  public LeafCollector getLeafCollector(LeafReaderContext context)
      throws IOException {
    return mainCollector.getLeafCollector(context);
  }

  @Override
  public boolean needsScores() {
    return true;
  }

  @Override
  protected int topDocsSize() {
    return reRankDocs;
  }

  @Override
  public int getTotalHits() {
    return mainCollector.getTotalHits();
  }

  @SuppressWarnings("unchecked")
  @Override
  public TopDocs topDocs(int start, int howMany) {
    try {
      if (howMany > reRankDocs) {
        howMany = reRankDocs;
      }
      final TopDocs mainDocs = mainCollector.topDocs(0, reRankDocs);
      TopDocs topRerankDocs = reRankRescorer.rescore(searcher,
          mainDocs, howMany);
      if (boostedPriority != null) {
        final SolrRequestInfo info = SolrRequestInfo.getRequestInfo();
        Map requestContext = null;
        if (info != null) {
          requestContext = info.getReq().getContext();
        }

        final IntIntHashMap boostedDocs = QueryElevationComponent.getBoostDocs(
            (SolrIndexSearcher) searcher, boostedPriority, requestContext);

        Arrays.sort(topRerankDocs.scoreDocs, new BoostedComp(boostedDocs,
            mainDocs.scoreDocs, topRerankDocs.getMaxScore()));

        final ScoreDoc[] scoreDocs = new ScoreDoc[howMany];
        System.arraycopy(topRerankDocs.scoreDocs, 0, scoreDocs, 0, howMany);
        topRerankDocs.scoreDocs = scoreDocs;
      }
      return topRerankDocs;

    } catch (final Exception e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
    }
  }

  public class BoostedComp implements Comparator {
    IntFloatHashMap boostedMap;

    public BoostedComp(IntIntHashMap boostedDocs, ScoreDoc[] scoreDocs,
        float maxScore) {
      boostedMap = new IntFloatHashMap(boostedDocs.size() * 2);

      for (final ScoreDoc scoreDoc : scoreDocs) {
        final int idx;
        if ((idx = boostedDocs.indexOf(scoreDoc.doc)) >= 0) {
          boostedMap.put(scoreDoc.doc, maxScore + boostedDocs.indexGet(idx));
        } else {
          break;
        }
      }
    }

    @Override
    public int compare(Object o1, Object o2) {
      final ScoreDoc doc1 = (ScoreDoc) o1;
      final ScoreDoc doc2 = (ScoreDoc) o2;
      float score1 = doc1.score;
      float score2 = doc2.score;
      int idx;
      if ((idx = boostedMap.indexOf(doc1.doc)) >= 0) {
        score1 = boostedMap.indexGet(idx);
      }

      if ((idx = boostedMap.indexOf(doc2.doc)) >= 0) {
        score2 = boostedMap.indexGet(idx);
      }

      return -Float.compare(score1, score2);
    }
  }

}
