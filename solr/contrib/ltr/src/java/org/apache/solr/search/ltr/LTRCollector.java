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
package org.apache.solr.search.ltr;

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
  private final int length;
  private final Map<BytesRef,Integer> boostedPriority;

  @SuppressWarnings("unchecked")
  public LTRCollector(int reRankDocs, int length,
      Rescorer reRankRescorer, QueryCommand cmd,
      IndexSearcher searcher, Map<BytesRef,Integer> boostedPriority)
      throws IOException {
    super(null);
    this.reRankRescorer = reRankRescorer;
    this.reRankDocs = reRankDocs;
    this.length = length;
    this.boostedPriority = boostedPriority;
    Sort sort = cmd.getSort();
    if (sort == null) {
      mainCollector = TopScoreDocCollector.create(Math.max(reRankDocs, length));
    } else {
      sort = sort.rewrite(searcher);
      mainCollector = TopFieldCollector.create(sort, Math.max(reRankDocs, length),
          false, true, true);
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
      // Use length instead of howMany for caching purposes
      TopDocs mainDocs = mainCollector.topDocs(0,  Math.max(reRankDocs, length));

      if(mainDocs.totalHits == 0 || mainDocs.scoreDocs.length == 0) {
        return mainDocs;
      }

      ScoreDoc[] mainScoreDocs = mainDocs.scoreDocs;      

      // Create the array for the reRankScoreDocs.
      // TODO: Shouldn't this be reRankDocs size?  If so it is broken in 
      //       Solr's ReRankQuery as well
      ScoreDoc[] reRankScoreDocs = new ScoreDoc[Math.min(mainScoreDocs.length, reRankDocs)];

      // Copy the initial results into the reRankScoreDocs array.
      // TODO: Any way to avoid the copy for truncation to generate less garbage, like using
      //       a length variable instead of array.length below?  
      //       This would need to be changed in Solr's ReRankQuery too
      System.arraycopy(mainScoreDocs, 0, reRankScoreDocs, 0, reRankScoreDocs.length);
      
      mainDocs.scoreDocs = reRankScoreDocs;
      
      TopDocs rescoredDocs = reRankRescorer.rescore(
          searcher, mainDocs, mainDocs.scoreDocs.length);
      
      if(boostedPriority != null) {
        SolrRequestInfo info = SolrRequestInfo.getRequestInfo();
        Map requestContext = null;
        if(info != null) {
          requestContext = info.getReq().getContext();
        }

        IntIntHashMap boostedDocs = QueryElevationComponent.getBoostDocs((SolrIndexSearcher)searcher, boostedPriority, requestContext);

        Arrays.sort(rescoredDocs.scoreDocs, new BoostedComp(boostedDocs, mainDocs.scoreDocs, rescoredDocs.getMaxScore()));
      }

      //Lower howMany to return if we've collected fewer documents.
      howMany = Math.min(howMany, mainScoreDocs.length);

      if(howMany == rescoredDocs.scoreDocs.length) {
        return rescoredDocs; // Just return the rescoredDocs
      } else if(howMany > rescoredDocs.scoreDocs.length) {
        //We need to return more then we've reRanked, so create the combined page.
        ScoreDoc[] scoreDocs = new ScoreDoc[howMany];
        //lay down the initial docs
        System.arraycopy(mainScoreDocs, 0, scoreDocs, 0, scoreDocs.length);
        //overlay the rescoreds docs
        System.arraycopy(rescoredDocs.scoreDocs, 0, scoreDocs, 0, rescoredDocs.scoreDocs.length);
        rescoredDocs.scoreDocs = scoreDocs;
        return rescoredDocs;
      } else {
        //We've rescored more then we need to return.
        ScoreDoc[] scoreDocs = new ScoreDoc[howMany];
        System.arraycopy(rescoredDocs.scoreDocs, 0, scoreDocs, 0, howMany);
        rescoredDocs.scoreDocs = scoreDocs;
        return rescoredDocs;
      }
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
    }
  }

  // TODO: Replace this with a shared copy for all Solr ReRankQueries
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
