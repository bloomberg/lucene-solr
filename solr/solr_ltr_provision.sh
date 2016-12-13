#!/bin/bash
Workdir=$PWD

BAR="###########################################################"
BARR="==========================================================="

## Default hostname
hostname="localhost"

## Read a Product name
Product_name="$1"


	if [[ $Product_name = "" ]];then
		echo $BAR
		echo " Please insert a Product name as you need: (Default is techproducts) "
		echo $BAR
		read Product_name
		echo $BAR
			if [[ $Product_name = "" ]];then
					echo "$BARR"
					echo "Product name will be \"techproducts\" automatically"
					echo "$BARR"
					Product_name="techproducts"
			fi
	fi  

## Kill the processor before that start
ps aux | grep "solr" | egrep -v "grep" | grep "java" | awk '{print $2}' | xargs kill -9

## Set the home directory
home_dir="$Workdir/example/$Product_name/solr"


## Check the home directory exist
	if [[ -d $home_dir ]];then
		echo "$BAR"	
		echo " Do you need to remove this dir? -> $home_dir [y /n]"
		read QA
			if [[ $QA = "y" ]];then
				rm -Rfv $home_dir
			else 
				exit 1 
			fi 	
	fi

## Check the solr processor if that existed
ps_result=`ps aux | grep "solr" | egrep -v "grep" | grep "java" | awk '{print $2}'`
	if [[ $ps_result != "" ]];then
		echo "$BAR"
		echo " Processor is working now !!!"
		echo "$BAR"
		exit
	fi

# Run the example to setup the index
$Workdir/bin/solr -e $Product_name -h 127.0.0.1

# Stop solr and install the plugin:
# Stop solr
$Workdir/bin/solr stop
# Create the lib folder
mkdir -p $Workdir/example/$Product_name/solr/$Product_name/lib

# Install the plugin in the lib folder
cp -Rf $Workdir/build/contrib/solr-ltr/solr-ltr-7.0.0-SNAPSHOT.jar $Workdir/example/$Product_name/solr/$Product_name/lib/

# Replace the original solrconfig with one importing all the ltr components
cp -Rf $Workdir/contrib/ltr/example/solrconfig.xml $Workdir/example/$Product_name/solr/$Product_name/conf/

# Run the example again
$Workdir/bin/solr -e $Product_name -h 127.0.0.1

# Note you could also have just restarted your collection using the admin page.
# You can find more detailed instructions here.
# Deploy features and a model
curl -XPUT "http://$hostname:8983/solr/$Product_name/schema/feature-store"  --data-binary "@$Workdir/contrib/ltr/example/$Product_name-features.json"  -H 'Content-type:application/json'
curl -XPUT "http://$hostname:8983/solr/$Product_name/schema/model-store"  --data-binary "@$Workdir/contrib/ltr/example/$Product_name-model.json"  -H 'Content-type:application/json'

# Access to the default feature store
curl -sL "http://$hostname:8983/solr/$Product_name/schema/feature-store/_DEFAULT_"

# Access to the model store
curl -sL "http://$hostname:8983/solr/$Product_name/schema/model-store"

# Perform a reranking query using the model, and retrieve the features
curl -sL "http://$hostname:8983/solr/$Product_name/query?indent=on&q=test&wt=json&rq={!ltr%20model=linear%20reRankDocs=25%20efi.user_query=%27test%27}&fl=[features],price,score,name"

