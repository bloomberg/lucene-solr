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
package org.apache.solr.search.grouping;

import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.grouping.CollectedSearchGroup;
import org.apache.lucene.search.grouping.FirstPassGroupingCollector;
import org.apache.lucene.search.grouping.GroupSelector;
import org.apache.lucene.search.grouping.SearchGroup;

/**
 * A {@link FirstPassGroupingCollector} that gathers extra information so that (in certain scenarios)
 * the second pass grouping can be skipped.
 */
public class SkipSecondPassFirstPassGroupingCollector<T> extends FirstPassGroupingCollector<T> {

  /**
   * A {@link SearchGroup} that contains extra information so that (in certain scenarios)
   * the second pass grouping can be skipped.
   */
  public static class SolrSearchGroup<T> extends org.apache.lucene.search.grouping.SearchGroup<T> {

    public int topDocLuceneId;
    public float topDocScore;
    public Object topDocSolrId;

    @Override
    protected MergedGroup<T> newMergedGroup() {
      SolrMergedGroup<T> mergedGroup = new SolrMergedGroup<>(this.groupValue);
      mergedGroup.topDocScore = this.topDocScore;
      mergedGroup.topDocSolrId = this.topDocSolrId;
      return mergedGroup;
    }

    private static class SolrMergedGroup<T> extends org.apache.lucene.search.grouping.SearchGroup.MergedGroup<T> {

      public float topDocScore;
      public Object topDocSolrId;

      public SolrMergedGroup(T groupValue) {
        super(groupValue);
      }

      @Override
      protected SearchGroup<T> newSearchGroup() {
        return new SolrSearchGroup<T>();
      }

      @Override
      protected void fillSearchGroup(SearchGroup<T> searchGroup) {
        super.fillSearchGroup(searchGroup);
        ((SolrSearchGroup<T>)searchGroup).topDocScore = this.topDocScore;
        ((SolrSearchGroup<T>)searchGroup).topDocSolrId = this.topDocSolrId;
      }

      @Override
      public void update(SearchGroup<T> searchGroup) {
        super.update(searchGroup);
        this.topDocScore = ((SolrSearchGroup<T>)searchGroup).topDocScore;
        this.topDocSolrId = ((SolrSearchGroup<T>)searchGroup).topDocSolrId;
      }

    }

  }

  public SkipSecondPassFirstPassGroupingCollector(GroupSelector<T> groupSelector, Sort groupSort, int topNGroups) {
    super(groupSelector, groupSort, topNGroups);
  }

  @Override
  protected SearchGroup<T> newSearchGroup() {
    return new SolrSearchGroup<>();
  }

  @Override
  protected SearchGroup<T> newSearchGroupFromCollectedSearchGroup(
      CollectedSearchGroup<T> group,
      FieldComparator<?>[] comparators,
      int sortFieldCount) {

    final SearchGroup<T> searchGroup = super.newSearchGroupFromCollectedSearchGroup(group, comparators, sortFieldCount);
    final SolrSearchGroup<T> solrSearchGroup = (SolrSearchGroup<T>)searchGroup;

    solrSearchGroup.topDocLuceneId = group.topDoc;

    solrSearchGroup.topDocScore = Float.NaN;
    // if there is the score comparator we want to return the score
    for (FieldComparator comparator: comparators) {
      if (comparator instanceof FieldComparator.RelevanceComparator) {
        solrSearchGroup.topDocScore = (Float)comparator.value(group.comparatorSlot);
      }
    }

    return solrSearchGroup;
  }

}
