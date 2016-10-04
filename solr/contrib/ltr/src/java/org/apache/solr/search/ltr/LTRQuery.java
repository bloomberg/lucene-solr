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

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.solr.ltr.ranking.ModelQuery;
import org.apache.solr.search.AbstractReRankQuery;
import org.apache.solr.search.RankQuery;


/**
 * The LTRQuery and LTRWeight wrap the main query to fetch matching docs. It
 * then provides its own TopDocsCollector, which goes through the top X docs and
 * reranks them using the provided reRankModel.
 */
public class LTRQuery extends AbstractReRankQuery {
  private static Query defaultQuery = new MatchAllDocsQuery();
  private final ModelQuery reRankModel;

  public LTRQuery(ModelQuery reRankModel, int reRankDocs) {
    super(defaultQuery, reRankDocs, new LTRRescorer(reRankModel));
    this.reRankModel = reRankModel;
  }

  @Override
  public int hashCode() {
    return 31 * classHash() + (mainQuery.hashCode() + reRankModel.hashCode() + reRankDocs);
  }

  @Override
  public boolean equals(Object o) {
    return sameClassAs(o) &&  equalsTo(getClass().cast(o));
  }

  private boolean equalsTo(LTRQuery other) {    
    return (mainQuery.equals(other.mainQuery)
        && reRankModel.equals(other.reRankModel) && (reRankDocs == other.reRankDocs));
  }

  @Override
  public RankQuery wrap(Query _mainQuery) {
    super.wrap(_mainQuery);    
    reRankModel.setOriginalQuery(_mainQuery);
    return this;
  }

  @Override
  public String toString(String field) {
    return "{!ltr mainQuery='" + mainQuery.toString() + "' reRankModel='"
        + reRankModel.toString() + "' reRankDocs=" + reRankDocs + "}";
  }
  
  @Override
  protected Query rewrite(Query rewrittenMainQuery) throws IOException {
    return new LTRQuery(reRankModel, reRankDocs).wrap(rewrittenMainQuery);
  }
}
