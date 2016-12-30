This README file is only about this example directory's content.

Please refer to the Solr Reference Guide's section on [Result Reranking](https://cwiki.apache.org/confluence/display/solr/Result+Reranking) section for broader information on Learning to Rank (LTR) with Apache Solr.

# Train an example machine learning model using LIBLINEAR

1. Download and install [liblinear](https://www.csie.ntu.edu.tw/~cjlin/liblinear/)

2. Change `contrib/ltr/example/config.json` "trainingLibraryLocation" to point to the train directory where you installed liblinear.

3. Extract features, train a reranking model, and deploy it to Solr.

  `cd contrib/ltr/example`

  `python train_and_upload_demo_model.py -c config.json`

   This script deploys your features from `config.json` "featuresFile" to Solr.  Then it takes the relevance judged query
   document pairs of "userQueriesFile" and merges it with the features extracted from Solr into a training
   file.  That file is used to train a linear model, which is then deployed to Solr for you to rerank results.

4. Search and rerank the results using the trained model

   http://localhost:8983/solr/techproducts/query?indent=on&q=test&wt=json&rq={!ltr%20model=exampleModel%20reRankDocs=25%20efi.user_query=%27test%27}&fl=price,score,name

# Assemble training data
In order to train a learning to rank model you need training data. Training data is
what "teaches" the model what the appropriate weight for each feature is. In general
training data is a collection of queries with associated documents and what their ranking/score
should be. As an example:
```
secretary of state|John Kerry|0.66|CROWDSOURCE
secretary of state|Cesar A. Perales|0.33|CROWDSOURCE
secretary of state|New York State|0.0|CROWDSOURCE
secretary of state|Colorado State University Secretary|0.0|CROWDSOURCE

microsoft ceo|Satya Nadella|1.0|CLICK_LOG
microsoft ceo|Microsoft|0.0|CLICK_LOG
microsoft ceo|State|0.0|CLICK_LOG
microsoft ceo|Secretary|0.0|CLICK_LOG
```
In this example the first column indicates the query, the second column indicates a unique id for that doc,
the third column indicates the relative importance or relevance of that doc, and the fourth column indicates the source.
There are 2 primary ways you might collect data for use with your machine learning algorithim. The first
is to collect the clicks of your users given a specific query. There are many ways of preparing this data
to train a model (http://www.cs.cornell.edu/people/tj/publications/joachims_etal_05a.pdf). The general idea
is that if a user sees multiple documents and clicks the one lower down, that document should be scored higher
than the one above it. The second way is explicitly through a crowdsourcing platform like Mechanical Turk or
CrowdFlower. These platforms allow you to show human workers documents associated with a query and have them
tell you what the correct ranking should be.

At this point you'll need to collect feature vectors for each query document pair. You can use the information
from the Extract features section above to do this. An example script has been included in example/train_and_upload_demo_model.py.
