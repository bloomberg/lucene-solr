This README file is only about this example directory's content.

Please refer to the Solr Reference Guide's section on [Result Reranking](https://cwiki.apache.org/confluence/display/solr/Result+Reranking) section for broader information on Learning to Rank (LTR) with Apache Solr.

# Start Solr with the LTR plugin enabled

   `./bin/solr -e techproducts -Dsolr.ltr.enabled=true`

# Train an example machine learning model using LIBLINEAR

1. Download and install [liblinear](https://www.csie.ntu.edu.tw/~cjlin/liblinear/)

2. Change `contrib/ltr/example/config.json` "trainingLibraryLocation" to point to the train directory where you installed liblinear.

   Alternatively, leave the `config.json` file unchanged and create a soft-link to your `liblinear` directory e.g.

  `ln -s /Users/YourNameHere/Downloads/liblinear-2.1 ./contrib/ltr/example/liblinear`

3. Extract features, train a reranking model, and deploy it to Solr.

  `cd contrib/ltr/example`

  `python train_and_upload_demo_model.py -c config.json`

   This script deploys your features from `config.json` "solrFeaturesFile" to Solr.  Then it takes the relevance judged query
   document pairs of "userQueriesFile" and merges it with the features extracted from Solr into a training
   file.  That file is used to train a linear model, which is then deployed to Solr for you to rerank results.

4. Search and rerank the results using the trained model

```
http://localhost:8983/solr/techproducts/query?indent=on&q=test&wt=json&rq={!ltr%20model=exampleModel%20reRankDocs=25%20efi.user_query=%27test%27}&fl=price,score,name
```

# Assemble training data
In order to train a learning to rank model you need training data. Training data is
what *teaches* the model what the appropriate weight for each feature is. In general
training data is a collection of queries with associated documents and what their ranking/score
should be. As an example:
```
hard drive|SP2514N|0.6666666|CLICK_LOGS
hard drive|6H500F0|0.330082034|CLICK_LOGS
hard drive|F8V7067-APL-KIT|0.0|CLICK_LOGS
hard drive|IW-02|0.0|CLICK_LOGS

ipod|MA147LL/A|1.0|HUMAN_JUDGEMENT
ipod|F8V7067-APL-KIT|0.5|HUMAN_JUDGEMENT
ipod|IW-02|0.5|HUMAN_JUDGEMENT
ipod|6H500F0|0.0|HUMAN_JUDGEMENT
```
The columns in the example represent:

  1. the user query;

  2. a unique id for a document in the response;

  3. the a score representing the relevance of that document (not necessarily between zero and one);

  4. the source, i.e., if the training record was produced by using interaction data (`CLICK_LOGS`) or by human judgements (`HUMAN_JUDGEMENT`).

## How to produce training data

You might collect data for use with your machine learning algorithm relying on:

  * **Users Interactions**: given a specific query, it is possible to log all the users interactions (e.g., clicks, shares on social networks, send by email etc), and then use them as proxies for relevance;
  * **Human Judgments**: A training dataset is produced by explitely asking some judges to evaluate the relavance of a document given the query.

### How to prepare training data from interaction data?

There are many ways of preparing interaction data for training a model, and it is outside
the scope of this readme to provide a complete review of all the techniques.
In the following are illustrated some simple techniques for obtaining training
data from simple interaction data.

Simple interaction data will be a Solr log providing:

  * When a user performs a query we have a record with `user-id, query, responses`,
  where `responses` is a list of unique document ids returned for a query.

**Example:**

```
query: diego, ipod, [SP2514N,6H500F0,F8V7067-APL-KIT,IW-02]
```

  * When a user performs a click we have a record with `user-id, document-id, click`

**Example:**
```
click: diego,   P2515N
click: michael, P215N
click: joshua,  IW-02`.
```

Given a log composed by records like these, a simple way to produce a training dataset is to group on the query field
and then assign to each query a relevance score equals to the number of clicks:

```
hard drive|SP2514N|2|CLICK_LOGS
hard drive|IW-02|1|CLICK_LOGS
hard drive|6H500F0|0|CLICK_LOGS
hard drive|F8V7067-APL-KIT|0|CLICK_LOGS
```

This is a really trival way to generate a training dataset, and in many settings it will not work.
Indeed, it is a well known fact that clicks are *biased*: users tend to click  on the first
results proposed for a query, also if is not relevant. A click on a document in position
five should be considered more important than a click on a document in position one, because
the user took the effort to browse the results list until position five.
Some approaches take into account the time spent on the clicked document (if the user
spent only two seconds on the document and then clicked on other documents in the list,
probably she did not intend to click that document).

There are many papers proposing techniques for removing the bias, or for taking into account the click positions,
a good survey is  [Click Models for Web Search](http://clickmodels.weebly.com/uploads/5/2/2/5/52257029/mc2015-clickmodels.pdf),
by Chuklin, Markov and Rijke.

### Prepare training data from human judgments

Another way to get training data is asking human judgements to label them.
Producing human judgments is in general more expensive, but the quality of the
dataset produced is usually better than the one produced from interaction data.
It is worth to note that human judgements can be produced also relying on a
crowdsourcing platform like Mechanical Turk or CrowdFlower.
These platforms allow a user to show human workers documents associated with a
query and have them tell you what the correct ranking should be.
