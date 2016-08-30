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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;

import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.Explanation;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Rescorer;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.solr.ltr.log.FeatureLogger;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight;
import org.apache.solr.ltr.ranking.ModelQuery.ModelWeight.ModelScorer;
import org.apache.solr.ltr.util.CommonLTRParams;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the rescoring logic. The top documents returned by solr with their
 * original scores, will be processed by a {@link ModelQuery} that will assign a
 * new score to each document. The top documents will be resorted based on the
 * new score.
 * */
public class LTRRescorer extends Rescorer {

  ModelQuery reRankModel;
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public LTRRescorer(ModelQuery reRankModel) {
    this.reRankModel = reRankModel;
  }

  private void heapAdjust(ScoreDoc[] hits, int size, int root) {
    final ScoreDoc doc = hits[root];
    final float score = doc.score;
    int i = root;
    while (i <= ((size >> 1) - 1)) {
      final int lchild = (i << 1) + 1;
      final ScoreDoc ldoc = hits[lchild];
      final float lscore = ldoc.score;
      float rscore = Float.MAX_VALUE;
      final int rchild = (i << 1) + 2;
      ScoreDoc rdoc = null;
      if (rchild < size) {
        rdoc = hits[rchild];
        rscore = rdoc.score;
      }
      if (lscore < score) {
        if (rscore < lscore) {
          hits[i] = rdoc;
          hits[rchild] = doc;
          i = rchild;
        } else {
          hits[i] = ldoc;
          hits[lchild] = doc;
          i = lchild;
        }
      } else if (rscore < score) {
        hits[i] = rdoc;
        hits[rchild] = doc;
        i = rchild;
      } else {
        return;
      }
    }
  }

  private void heapify(ScoreDoc[] hits, int size) {
    for (int i = (size >> 1) - 1; i >= 0; i--) {
      heapAdjust(hits, size, i);
    }
  }

  /**
   * rescores the documents:
   *
   * @param searcher
   *          current IndexSearcher
   * @param firstPassTopDocs
   *          documents to rerank;
   * @param topN
   *          documents to return;
   */
  @Override
  public TopDocs rescore(IndexSearcher searcher, TopDocs firstPassTopDocs,
      int topN) throws IOException {
    if ((topN == 0) || (firstPassTopDocs.totalHits == 0)) {
      return firstPassTopDocs;
    }
    
    final ScoreDoc[] hits = firstPassTopDocs.scoreDocs;
   

    Arrays.sort(hits, new Comparator<ScoreDoc>() {
      @Override
      public int compare(ScoreDoc a, ScoreDoc b) {
        return a.doc - b.doc;
      }
    });

    topN = Math.min(topN, firstPassTopDocs.totalHits);
    final ScoreDoc[] reranked = new ScoreDoc[topN];
    final List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
    final ModelWeight modelWeight = (ModelWeight) searcher
        .createNormalizedWeight(reRankModel, true);

    // FIXME: I dislike that we have no gaurentee this is actually a
    // SolrIndexReader.
    // We should do something about that
    final SolrIndexSearcher solrIndexSearch = (SolrIndexSearcher) searcher;
    if (LTRThreadInterface.maxThreads > 1){
       scoreParallel(solrIndexSearch, firstPassTopDocs,topN, modelWeight, hits, leaves, reranked);
    }
    else{
       scoreSimple(solrIndexSearch, firstPassTopDocs,topN, modelWeight, hits, leaves, reranked);
    }
    // Must sort all documents that we reranked, and then select the top 
    Arrays.sort(reranked, new Comparator<ScoreDoc>() {
      @Override
      public int compare(ScoreDoc a, ScoreDoc b) {
        // Sort by score descending, then docID ascending:
        if (a.score > b.score) {
          return -1;
        } else if (a.score < b.score) {
          return 1;
        } else {
          // This subtraction can't overflow int
          // because docIDs are >= 0:
          return a.doc - b.doc;
        }
      }
    });

    // if (topN < hits.length) {
    // ScoreDoc[] subset = new ScoreDoc[topN];
    // System.arraycopy(hits, 0, subset, 0, topN);
    // hits = subset;
    // }

    return new TopDocs(firstPassTopDocs.totalHits, reranked, reranked[0].score);
  }
  
  public void scoreSimple(SolrIndexSearcher solrIndexSearch, TopDocs firstPassTopDocs,
      int topN, ModelWeight modelWeight, ScoreDoc[] hits, List<LeafReaderContext> leaves,
      ScoreDoc[] reranked) throws IOException {
    


    int readerUpto = -1;
    int endDoc = 0;
    int docBase = 0;

    ModelScorer scorer = null;
    int hitUpto = 0;
    final FeatureLogger<?> featureLogger = reRankModel.getFeatureLogger();

    // FIXME
    // All of this heap code is only for logging. Wrap all this code in
    // 1 outer if (fl != null) so we can skip heap stuff if the request doesn't
    // call for a feature vector.
    //
    // that could be done but it would require a new vector of size $rerank,
    // that in the end we would have to sort, while using the heap, also if
    // we do not log, in the end we sort a smaller array of topN results (that
    // is the heap array).
    // The heap is just anticipating the sorting of the array, so I don't think
    // it would
    // save time.
    long time1 = System.currentTimeMillis();
    while (hitUpto < hits.length) {
      final ScoreDoc hit = hits[hitUpto];
      final int docID = hit.doc;
      
      LeafReaderContext readerContext = null;
      while (docID >= endDoc) {
        readerUpto++;
        readerContext = leaves.get(readerUpto);
        endDoc = readerContext.docBase + readerContext.reader().maxDoc();
      }
      long stime1 = System.currentTimeMillis();
      // We advanced to another segment
      if (readerContext != null) {
        docBase = readerContext.docBase;
        scorer = modelWeight.scorer(readerContext);
      }
      long stime2 = System.currentTimeMillis();
      // Scorer for a ModelWeight should never be null since we always have to
      // call score
      // even if no feature scorers match, since a model might use that info to
      // return a
      // non-zero score. Same applies for the case of advancing a ModelScorer
      // past the target
      // doc since the model algorithm still needs to compute a potentially
      // non-zero score from blank features.
      assert (scorer != null);
      final int targetDoc = docID - docBase;
      scorer.docID();
      scorer.iterator().advance(targetDoc);

      scorer.setDocInfoParam(CommonLTRParams.ORIGINAL_DOC_SCORE, new Float(hit.score));
      long stime3 = System.currentTimeMillis();
      hit.score = scorer.score();
      if (hitUpto < topN) {
        reranked[hitUpto] = hit;
        // if the heap is not full, maybe I want to log the features for this
        // document
        if (featureLogger != null) {
          featureLogger.log(hit.doc, reRankModel, solrIndexSearch,
              scorer.featuresInfo);
        }
      } else if (hitUpto == topN) {
        // collected topN document, I create the heap
        heapify(reranked, topN);
      }
      if (hitUpto >= topN) {
        // once that heap is ready, if the score of this document is lower that
        // the minimum
        // i don't want to log the feature. Otherwise I replace it with the
        // minimum and fix the
        // heap.
        if (hit.score > reranked[0].score) {
          reranked[0] = hit;
          heapAdjust(reranked, topN, 0);
          if (featureLogger != null) {
            featureLogger.log(hit.doc, reRankModel, solrIndexSearch,
                scorer.featuresInfo);
          }
        }
      }
      log.info("In scorer loop: create scorer: {} score time: {}", (stime2-stime1), (stime3-stime2));
      hitUpto++;
    }
    long time2 = System.currentTimeMillis();
    log.info("Total rescore time: {}", (time2-time1));
    
  }
  
  class SegmentScores{
    List<ScoreDoc> scoreDocList = new ArrayList<ScoreDoc>();
    LeafReaderContext readerContext = null;
    ModelScorer scorer = null;

    public SegmentScores(LeafReaderContext readerContext){
       this.readerContext = readerContext;
    }
    
    public void setScorer(ModelScorer sc){
      this.scorer = sc;
    }
    
    public ModelScorer getScorer(){
      return this.scorer;
    }

    public void setScoreDocs(List<ScoreDoc> scoreDocs){
       this.scoreDocList = scoreDocs;
    }

    public List<ScoreDoc> getScoreDocs(){
       return this.scoreDocList;
    }

    public LeafReaderContext getContext(){
       return readerContext;
    }
}

class SegmentScorerCallable implements Callable<SegmentScores>{
      private SegmentScores segScores = null;
      private ModelWeight modelWeight = null;
      private LeafReaderContext readerContext = null;
      
      public SegmentScorerCallable(SegmentScores segscores, ModelWeight modelWeight){
         this.segScores = segscores;
         this.modelWeight = modelWeight;
         this.readerContext = this.segScores.getContext();
      }
      
      public SegmentScores call() throws Exception{
        try {
          long time1 = System.currentTimeMillis();
         int docBase = readerContext.docBase;
         ModelScorer scorer = modelWeight.scorer(readerContext);
         segScores.setScorer(scorer);
         for (final ScoreDoc sd: segScores.getScoreDocs()){
            final int targetDoc = sd.doc - docBase;
            scorer.docID(); /// ???? why is this needed?
            scorer.iterator().advance(targetDoc);
            scorer.setDocInfoParam(CommonLTRParams.ORIGINAL_DOC_SCORE, new Float(sd.score));
            sd.score = scorer.score();
         }
         long time2 = System.currentTimeMillis();
         log.info("\tTotal SegmentScorerCallable call time: {}", (time2-time1));
         return this.segScores;
        } catch (final Exception se) {
          throw new Exception("Exception from createscorer for " + segScores.toString() + " "
          + se.getMessage(), se);
        } finally {
          LTRThreadInterface.ltrSemaphore.release();
        }
      }
} // end of call SegmentScorerCallable
  
  private void scoreParallel(SolrIndexSearcher solrIndexSearch, TopDocs firstPassTopDocs,
      int topN, ModelWeight modelWeight, ScoreDoc[] hits, List<LeafReaderContext> leaves,
      ScoreDoc[] reranked) throws IOException{

    int readerUpto = -1;
    int endDoc = 0;
    int hitUpto = 0;

    final FeatureLogger<?> featureLogger = reRankModel.getFeatureLogger();

    // FIXME
    // All of this heap code is only for logging. Wrap all this code in
    // 1 outer if (fl != null) so we can skip heap stuff if the request doesn't
    // call for a feature vector.
    //
    // that could be done but it would require a new vector of size $rerank,
    // that in the end we would have to sort, while using the heap, also if
    // we do not log, in the end we sort a smaller array of topN results (that
    // is the heap array).
    // The heap is just anticipating the sorting of the array, so I don't think
    // it would
    // save time.
    long time1 = System.currentTimeMillis();
    List< SegmentScores > segmentScoresList = new ArrayList< SegmentScores >();// one list per segment
    SegmentScores segmentScores = null;
    List<ScoreDoc> scoreDocList = new ArrayList<ScoreDoc>();

    while (hitUpto < hits.length) {
       final ScoreDoc hit = hits[hitUpto];
       final int docID = hit.doc;
       LeafReaderContext readerContext = null;
       if (docID < endDoc){  // if it is the current segment, add the doc to the list for this segment
         scoreDocList.add(hit);
         hitUpto++;
         continue;
       }
       else if (scoreDocList.size() > 0){ // if docid is greater then the end of current segment, add the current segmentScore to segmentScoresList
         List<ScoreDoc> tempScoreDocList = new ArrayList<ScoreDoc>(scoreDocList);
         segmentScores.setScoreDocs(tempScoreDocList);
         segmentScoresList.add(segmentScores);
         scoreDocList.clear();
       }
       while (docID >= endDoc) { // find the segment for this document
          readerUpto++;
          readerContext = leaves.get(readerUpto);
          endDoc = readerContext.docBase + readerContext.reader().maxDoc();
       }
       if (readerContext != null){
          segmentScores = new SegmentScores(readerContext);
          scoreDocList.add(hit);
       }
       hitUpto++;
    }
    if (scoreDocList.size() > 0){ // copy the last list
      List<ScoreDoc> tempScoreDocList = new ArrayList<ScoreDoc>(scoreDocList);
      segmentScores.setScoreDocs(tempScoreDocList);
      segmentScoresList.add(segmentScores);
      scoreDocList.clear();
    }
    Executor executor = LTRThreadInterface.createWeightScoreExecutor;

    List<Future<SegmentScores> > futures = new ArrayList<>(segmentScoresList.size());
    List< SegmentScores > updatedSegmentScoresList = new ArrayList< SegmentScores >();

    try{
      for (SegmentScores segScores:segmentScoresList){
        SegmentScorerCallable callable = new SegmentScorerCallable(segScores, modelWeight);
        RunnableFuture<SegmentScores> runnableFuture = new FutureTask<>(callable);
        LTRThreadInterface.ltrSemaphore.acquire();//may block and/or interrupt
        executor.execute(runnableFuture);//releases semaphore when done
        futures.add(runnableFuture);
      }
      //Loop over futures to get the SegmentScores objects
      for (final Future<SegmentScores> future : futures) {
        SegmentScores segScores = future.get();
        updatedSegmentScoresList.add(segScores);
      }
    } catch (InterruptedException e) {
      log.info("Error while creating scorers in LTR: InterruptedException", e);
    } catch (ExecutionException ee) {
      Throwable e = ee.getCause();//unwrap
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      log.info("Error while creating scorers in LTR: " + e.toString(), e);
    }
    
    hitUpto = 0;
    for (SegmentScores segScores:updatedSegmentScoresList) {
        for (final ScoreDoc hit:segScores.getScoreDocs()){
          if (hitUpto < topN) {
            reranked[hitUpto] = hit;
            // if the heap is not full, maybe I want to log the features for this
            // document
            if (featureLogger != null) {
              featureLogger.log(hit.doc, reRankModel, solrIndexSearch,
                  segScores.getScorer().featuresInfo);
            }
          } else if (hitUpto == topN) {
            // collected topN document, I create the heap
            heapify(reranked, topN);
          }
          if (hitUpto >= topN) {
            // once that heap is ready, if the score of this document is lower that
            // the minimum
            // i don't want to log the feature. Otherwise I replace it with the
            // minimum and fix the
            // heap.
            if (hit.score > reranked[0].score) {
              reranked[0] = hit;
              heapAdjust(reranked, topN, 0);
              if (featureLogger != null) {
                featureLogger.log(hit.doc, reRankModel, solrIndexSearch,
                    segScores.getScorer().featuresInfo);
              }
            }
          }
          //log.info("In scorer loop: create scorer: {} score time: {}", (stime2-stime1), (stime3-stime2));
          hitUpto++;  
      }
    }
    long time2 = System.currentTimeMillis();
    log.info("Total rescore time: {}", (time2-time1));
  }

  @Override
  public Explanation explain(IndexSearcher searcher,
      Explanation firstPassExplanation, int docID) throws IOException {

    final List<LeafReaderContext> leafContexts = searcher.getTopReaderContext()
        .leaves();
    final int n = ReaderUtil.subIndex(docID, leafContexts);
    final LeafReaderContext context = leafContexts.get(n);
    final int deBasedDoc = docID - context.docBase;
    final Weight modelWeight = searcher.createNormalizedWeight(reRankModel,
        true);
    return modelWeight.explain(context, deBasedDoc);
  }

}
