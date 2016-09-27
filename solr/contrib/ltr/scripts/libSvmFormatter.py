from subprocess import call
import os

PAIRWISE_THRESHOLD = 1.e-1
FEATURE_DIFF_THRESHOLD = 1.e-6

class LibSvmFormatter:
    def processQueryDocFeatureVector(self,docClickInfo,trainingFile):
        '''Expects as input a list or generator that provides the context for each query
         in a tuple composed of: query , docId , relevance , source , fv .
        Successive lines with the same query and source are assumed to be part of the same list'''
        curQueryAndSource = "";
        with open(trainingFile,"w") as output:
            self.featureNameToId  = {}
            self.featureIdToName = {}
            self.curFeatIndex = 1;
            curListOfFv = []
            for query,docId,relevance,source,fv in docClickInfo:
                if curQueryAndSource != query + source:
                    #Time to flush out all the pairs
                    _writeRankSVMPairs(curListOfFv,output);
                    curListOfFv = []
                    curQueryAndSource = query + source
                curListOfFv.append((relevance,self._makeFvMap(fv)))
            _writeRankSVMPairs(curListOfFv,output); #This catches the last list of comparisons

    def _makeFvMap(self,fvListForm):
        '''expects a list of strings with "feature name":"feature value" pairs. Outputs a map of map[key] = value.
        Where key is now an integer.libSVM requires the key to be an integer but not all libraries have
        this requirement.'''
        fv = {}
        for keyValuePairStr in fvListForm:
            featName,featValue = keyValuePairStr.split(":");
            fv[self._getFeatureId(featName)] = float(featValue);
        return fv

    def _getFeatureId(self,key):
        if key not in self.featureNameToId:
                self.featureNameToId[key] = self.curFeatIndex;
                self.featureIdToName[self.curFeatIndex] = key;
                self.curFeatIndex += 1;
        return self.featureNameToId[key];

    def convertLibSvmModelToLtrModel(self,libSvmModelLocation, outputFile, modelName):
        with open(libSvmModelLocation, 'r') as inFile:
            with open(outputFile,'w') as convertedOutFile:
                convertedOutFile.write('{\n\t"class":"org.apache.solr.ltr.ranking.RankSVMModel",\n')
                convertedOutFile.write('\t"name": "' + str(modelName) + '",\n')
                convertedOutFile.write('\t"features": [\n')
                isFirst = True;
                for featKey in self.featureNameToId.keys():
                    convertedOutFile.write('\t\t{ "name":"' + featKey  + '"}' if isFirst else ',\n\t\t{ "name":"' + featKey  + '"}' );
                    isFirst = False;
                convertedOutFile.write("\n\t],\n");
                convertedOutFile.write('\t"params": {\n\t\t"weights": {\n');

                startReading = False
                isFirst = True
                counter = 1
                for line in inFile:
                    if startReading:
                        newParamVal = float(line.strip())
                        if not isFirst:
                            convertedOutFile.write(',\n\t\t\t"' + self.featureIdToName[counter] + '":' + str(newParamVal))
                        else:
                            convertedOutFile.write('\t\t\t"' + self.featureIdToName[counter] + '":' + str(newParamVal))
                            isFirst = False
                        counter += 1
                    elif line.strip() == 'w':
                        startReading = True
                convertedOutFile.write('\n\t\t}\n\t}\n}')

def _writeRankSVMPairs(listOfFv,output):
    '''Given a list of relevance, {FV Map} where the list represents
    a set of documents to be compared, this calculates all pairs and
    writes the Feature Vectors in a format compatible with libSVM'''

    for d1 in range(0,len(listOfFv)):
                for d2 in range(d1+1,len(listOfFv)):
                    fv1,fv2 = listOfFv[d1][1],listOfFv[d2][1]
                    d1Relevance, d2Relevance = float(listOfFv[d1][0]),float(listOfFv[d2][0])
                    if  d1Relevance - d2Relevance > PAIRWISE_THRESHOLD:#d1Relevance > d2Relevance
                        outputLibSvmLine("+1",subtractFvMap(fv1,fv2),output);
                        outputLibSvmLine("-1",subtractFvMap(fv2,fv1),output);
                    elif d1Relevance - d2Relevance < -PAIRWISE_THRESHOLD: #d1Relevance < d2Relevance:
                        outputLibSvmLine("+1",subtractFvMap(fv2,fv1),output);
                        outputLibSvmLine("-1",subtractFvMap(fv1,fv2),output);
                    else: #Must be approximately equal relevance, in which case this is a useless signal and we should skip
                        continue;

def subtractFvMap(fv1,fv2):
    '''returns the fv from fv1 - fv2'''
    retFv = fv1.copy();
    for featInd in fv2.keys():
        subVal = 0.0;
        if featInd in fv1:
            subVal = fv1[featInd] - fv2[featInd]
        else:
            subVal = -fv2[featInd]
        if abs(subVal) > FEATURE_DIFF_THRESHOLD: #This ensures everything is in sparse format, and removes useless signals
            retFv[featInd] = subVal;
        else:
            retFv.pop(featInd, None)
    return retFv;

def outputLibSvmLine(sign,fvMap,outputFile):
    outputFile.write(sign)
    for feat in fvMap.keys():
        outputFile.write(" " + str(feat) + ":" + str(fvMap[feat]));
    outputFile.write("\n")

def trainLibSvm(libraryLocation,trainingFileName):
    if os.path.isfile(libraryLocation):
        call([libraryLocation, trainingFileName])
    else:
        raise Exception("NO LIBRARY FOUND: " + libraryLocation);
