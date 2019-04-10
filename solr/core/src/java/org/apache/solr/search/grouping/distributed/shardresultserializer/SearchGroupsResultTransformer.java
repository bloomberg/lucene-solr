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
package org.apache.solr.search.grouping.distributed.shardresultserializer;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.grouping.SearchGroup;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.grouping.Command;
import org.apache.solr.search.grouping.distributed.command.SearchGroupsFieldCommand;
import org.apache.solr.search.grouping.distributed.command.SearchGroupsFieldCommandResult;

import java.io.IOException;
import java.util.*;

/**
 * Implementation for transforming {@link SearchGroup} into a {@link NamedList} structure and visa versa.
 */
public class SearchGroupsResultTransformer implements ShardResultTransformer<List<Command>, Map<String, SearchGroupsFieldCommandResult>> {

  private static final String TOP_GROUPS = "topGroups";
  private static final String GROUP_COUNT = "groupCount";

  protected final SolrIndexSearcher searcher;
  private final SearchGroupsFieldCommandTransformer searchGroupsTransformer;

  public SearchGroupsResultTransformer(SolrIndexSearcher searcher) {
    this(searcher, false);
  }

  public SearchGroupsResultTransformer(SolrIndexSearcher searcher, boolean skipSecondStep) {
    this.searcher = searcher;
    if (skipSecondStep){
      searchGroupsTransformer = new SkipSecondStepSearchGroupsFieldCommandTransformer();
    } else {
      searchGroupsTransformer = new DefaultSearchGroupsFieldCommandTransformer();
    }
  }

  final protected Object[] getConvertedSortValues(final Object[] sortValues, final SortField[] sortFields) {
    Object[] convertedSortValues = new Object[sortValues.length];
    for (int i = 0; i < sortValues.length; i++) {
      Object sortValue = sortValues[i];
      SchemaField field = sortFields[i].getField() != null ? searcher.getSchema().getFieldOrNull(sortFields[i].getField()) : null;
      if (field != null) {
        FieldType fieldType = field.getType();
        if (sortValue != null) {
          sortValue = fieldType.marshalSortValue(sortValue);
        }
      }
      convertedSortValues[i] = sortValue;
    }
    return convertedSortValues;
  }

  @Override
  public NamedList transform(List<Command> data) throws IOException {
    final NamedList<NamedList> result = new NamedList<>(data.size());
    for (Command command : data) {
      final NamedList<Object> commandResult = new NamedList<>(2);
      if (SearchGroupsFieldCommand.class.isInstance(command)) {
        SearchGroupsFieldCommand fieldCommand = (SearchGroupsFieldCommand) command;
        final SearchGroupsFieldCommandResult fieldCommandResult = fieldCommand.result();
        final Collection<SearchGroup<BytesRef>> searchGroups = fieldCommandResult.getSearchGroups();
        if (searchGroups != null) {
          commandResult.add(TOP_GROUPS, searchGroupsTransformer.serializeSearchGroup(searchGroups, fieldCommand));
        }
        final Integer groupedCount = fieldCommandResult.getGroupCount();
        if (groupedCount != null) {
          commandResult.add(GROUP_COUNT, groupedCount);
        }
      } else {
        continue;
      }

      result.add(command.getKey(), commandResult);
    }
    return result;
  }

  @Override
  public Map<String, SearchGroupsFieldCommandResult> transformToNative(NamedList<NamedList> shardResponse, Sort groupSort, Sort withinGroupSort, String shard) {
    return searchGroupsTransformer.transformToNative(shardResponse, groupSort, withinGroupSort, shard);
  }

  // This method will convert a groupValue and a list of secondary values that are required to sort the documents within the group
  // to a SearchGroup representation
  private static SearchGroup<BytesRef> convertToSearchGroup(String groupFieldName, String groupFieldValue, Sort groupSort, List<?> sortValues, IndexSchema schema){
    SearchGroup<BytesRef> searchGroup = new SearchGroup<>();
    SchemaField groupField = groupFieldValue != null? schema.getFieldOrNull(groupFieldName) :    null;
    searchGroup.groupValue = null;
    if (groupFieldValue != null) {
      if (groupField != null) {
        // if we have the group field, we get the BytesRef representation of the fieldValue
        // according to the type of the field
        BytesRefBuilder builder = new BytesRefBuilder();
        groupField.getType().readableToIndexed(groupFieldValue, builder);
        searchGroup.groupValue = builder.get();
      } else {
        // ... otherwise we default to String
        searchGroup.groupValue = new BytesRef(groupFieldValue);
      }
    }
    // if the group is sorted according combining multiple fields, we need to retrieve
    // the values of all the fields that we are going to use

    // prepare the array with the sort values
    searchGroup.sortValues = sortValues.toArray(new Comparable[sortValues.size()]);
    for (int i = 0; i < searchGroup.sortValues.length; i++) {
      // for each field:
      // 1. we get the name of the field used by the current Sort
      final String sortField = groupSort.getSort()[i].getField();
      // we retrieve the schema field if possible
      final SchemaField field = (sortField != null) ? schema.getFieldOrNull(sortField) : null;
      // unmarshall the value according to the field if possible, otherwise use the original value
      searchGroup.sortValues[i] = ShardResultTransformerUtils.unmarshalSortValue(searchGroup.sortValues[i], field);
    }
    return searchGroup;
  }

  private interface SearchGroupsFieldCommandTransformer {
      Map<String, SearchGroupsFieldCommandResult> transformToNative(NamedList<NamedList> shardResponse, Sort groupSort, Sort withinGroupSort, String shard);
      NamedList serializeSearchGroup(Collection<SearchGroup<BytesRef>> data, SearchGroupsFieldCommand command);

    }

    class DefaultSearchGroupsFieldCommandTransformer implements SearchGroupsFieldCommandTransformer {

    public Map<String, SearchGroupsFieldCommandResult> transformToNative(NamedList<NamedList> shardResponse, Sort groupSort, Sort withinGroupSort, String shard) {
      final Map<String, SearchGroupsFieldCommandResult> result = new HashMap<>(shardResponse.size());
      final IndexSchema schema = searcher.getSchema();
      for (Map.Entry<String, NamedList> singleShardResponse : shardResponse) {
        final String groupFieldName = singleShardResponse.getKey();
        List<SearchGroup<BytesRef>> searchGroups = new ArrayList<>();
        NamedList topGroupsAndGroupCount = singleShardResponse.getValue();
        // this will return the raw list of groups, that the single shard returned
        @SuppressWarnings("unchecked")
        final NamedList<List<Comparable>> rawSearchGroups = (NamedList<List<Comparable>>) topGroupsAndGroupCount.get(TOP_GROUPS);
        if (rawSearchGroups != null) {
          // if the list exists, then we iterate over each group, the map represents
          // the identifier of the group (a query or a field value), as a key and a list of
          // the documents that belongs to the group.
          for (Map.Entry<String, List<Comparable>> rawSearchGroup : rawSearchGroups){
            // for each 'raw' search group (group-identifier + documents), we build a SearchGroup object and we add
            // it to searchGroups
            final String groupFieldValue = rawSearchGroup.getKey();
            List<Comparable> documentWithinTheSearchGroup = rawSearchGroup.getValue();
            SearchGroup<BytesRef> searchGroup = convertToSearchGroup(groupFieldName, groupFieldValue, groupSort, documentWithinTheSearchGroup, schema);
            searchGroups.add(searchGroup);
          }
        }
        // we get the total number of matched documents for this group
        final Integer groupCount = (Integer) topGroupsAndGroupCount.get(GROUP_COUNT);
        // we add them to the map
        result.put(singleShardResponse.getKey(), new SearchGroupsFieldCommandResult(groupCount, searchGroups));
      }
      return result;
    }

    public NamedList serializeSearchGroup(Collection<SearchGroup<BytesRef>> data, SearchGroupsFieldCommand command) {
      final NamedList<Object[]> result = new NamedList<>(data.size());

      for (SearchGroup<BytesRef> searchGroup : data) {
        Object[] convertedSortValues = new Object[searchGroup.sortValues.length];
        for (int i = 0; i < searchGroup.sortValues.length; i++) {
          Object sortValue = searchGroup.sortValues[i];
          SchemaField field = command.getGroupSort().getSort()[i].getField() != null ?
              searcher.getSchema().getFieldOrNull(command.getGroupSort().getSort()[i].getField()) : null;
          convertedSortValues[i] = ShardResultTransformerUtils.marshalSortValue(sortValue, field);
        }
        SchemaField field = searcher.getSchema().getFieldOrNull(command.getKey());
        String groupValue = searchGroup.groupValue != null ? field.getType().indexedToReadable(searchGroup.groupValue, new CharsRefBuilder()).toString() : null;
        result.add(groupValue, convertedSortValues);
      }

      return result;
    }
  }

  public class SkipSecondStepSearchGroupsFieldCommandTransformer implements SearchGroupsFieldCommandTransformer {

    private static final String TOP_DOC_SOLR_ID_KEY = "topDocSolrId";
    private static final String TOP_DOC_SCORE_KEY = "topDocScore";
    private static final String SORTVALUES_KEY  = "sortValues";

    @Override
    public Map<String, SearchGroupsFieldCommandResult> transformToNative(NamedList<NamedList> shardResponse, Sort groupSort, Sort withinGroupSort, String shard) {
      final Map<String, SearchGroupsFieldCommandResult> result = new HashMap<>(shardResponse.size());
      final IndexSchema schema = searcher.getSchema();

      for (Map.Entry<String, NamedList> singleShardResponse : shardResponse) {
        final String groupFieldName = singleShardResponse.getKey();
        List<SearchGroup<BytesRef>> searchGroups = new ArrayList<>();
        NamedList topGroupsAndGroupCount = singleShardResponse.getValue();
        @SuppressWarnings("unchecked")
        final NamedList<List<Comparable>> rawSearchGroups = (NamedList<List<Comparable>>) topGroupsAndGroupCount.get(TOP_GROUPS);
        if (rawSearchGroups != null) {
          // if the list exists, then we iterate over each group, the map represents
          // the identifier of the group (a query or a field value), as a key and a list of
          // the documents that belongs to the group.
          for (Map.Entry<String, List<Comparable>> rawSearchGroup : rawSearchGroups){
            // for each 'raw' search group (group-identifier + documents), we build a SearchGroup object and we add
            // it to searchGroups
            NamedList<Object> groupInfo = (NamedList) rawSearchGroup.getValue();
            final ArrayList<?> sortValues = (ArrayList<?>)groupInfo.get(SORTVALUES_KEY);
            final String groupFieldValue = rawSearchGroup.getKey();
            SearchGroup<BytesRef> searchGroup = convertToSearchGroup(groupFieldName, groupFieldValue, groupSort, sortValues, schema);

            searchGroup.topDocLuceneId = DocIdSetIterator.NO_MORE_DOCS;
            searchGroup.topDocScore = (float) groupInfo.get(TOP_DOC_SCORE_KEY);
            searchGroup.topDocSolrId = groupInfo.get(TOP_DOC_SOLR_ID_KEY);
            searchGroups.add(searchGroup);
          }
        }
        // we get the total number of matched documents for this group
        final Integer groupCount = (Integer) topGroupsAndGroupCount.get(GROUP_COUNT);
        // we add them to the map
        result.put(singleShardResponse.getKey(), new SearchGroupsFieldCommandResult(groupCount, searchGroups));
      }
      return result;
    }

    @Override
    public NamedList serializeSearchGroup(Collection<SearchGroup<BytesRef>> data, SearchGroupsFieldCommand command) {
      final NamedList<NamedList> result = new NamedList<>(data.size());
      for (SearchGroup<BytesRef> searchGroup : data) {

        final IndexSchema schema = searcher.getSchema();
        SchemaField uniqueField = schema.getUniqueKeyField();

        Document luceneDoc = null;
        /** Use the lucene id to get the unique solr id so that it can be sent to the federator.
         * The lucene id of a document is not unique across all shards i.e. different documents
         * in different shards could have the same lucene id, whereas the solr id is guaranteed
         * to be unique so this is what we need to return to the federator
        **/
        try {
          luceneDoc = retrieveDocument(uniqueField, searchGroup.topDocLuceneId);
        } catch (IOException e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Cannot retrieve document for unique field " + uniqueField + " ("+e.toString()+")");
        }
        Object topDocSolrId = uniqueField.getType().toExternal(luceneDoc.getField(uniqueField.getName()));
        NamedList<Object> groupInfo = new NamedList<>(5);
        groupInfo.add(TOP_DOC_SCORE_KEY, searchGroup.topDocScore);
        groupInfo.add(TOP_DOC_SOLR_ID_KEY, topDocSolrId);

        Object[] convertedSortValues = getConvertedSortValues(searchGroup.sortValues, command.getGroupSort().getSort());
        groupInfo.add(SORTVALUES_KEY, convertedSortValues);

        SchemaField field = searcher.getSchema().getFieldOrNull(command.getKey());
        final String groupValue = searchGroup.groupValue != null ? field.getType().indexedToReadable(searchGroup.groupValue, new CharsRefBuilder()).toString() : null;
        result.add(groupValue, groupInfo);
      }
      return result;
    }

    private Document retrieveDocument(final SchemaField uniqueField, int doc) throws IOException {
      DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor(uniqueField.getName());
      searcher.doc(doc, visitor);
      return visitor.getDocument();
    }
  }
}
