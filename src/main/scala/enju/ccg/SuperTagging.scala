package enju.ccg

import lexicon._
import tagger.{LF => Feature, _}

import scala.collection.mutable.ArraySeq
import scala.reflect.ClassTag
import java.io.{ObjectInputStream, ObjectOutputStream, FileWriter}

trait SuperTagging extends Problem {
  type DictionaryType <: Dictionary
  type WeightVector = ml.NumericBuffer[Float]

  var dict: DictionaryType = _
  var indexer: ml.FeatureIndexer[Feature] = _  // feature -> int
  var weights: WeightVector = _ // featureId -> weight; these 3 variables are serialized/deserialized

  def featureExtractors = { // you can change features by modifying this function
    val extractionMethods = Array(new UnigramWordExtractor(5), // unigram feature of window size 5
      // new BigramWordExtractor(3),
      new UnigramPoSExtractor(5),  // unigram pos feature of window size 5
      new BigramPoSExtractor(5))   // bigram pos feature using window size 5
    new FeatureExtractorsWithCustomPoSLevel(
      extractionMethods,
      dict.getWord("@@BOS@@"),
      dict.getPoS("@@NULL@@"),
      { pos => pos.secondWithConj.id })
    //new FeatureExtractors(extractionMethods, dict.getWord("@@BOS@@"), dict.getPoS("@@NULL@@"))
  }

  override def train = {
    dict = newDictionary

    println("Reading CCGBank...")
    val trainSentences = readSentencesFromCCGBank(trainPath, InputOptions.trainSize, true)
    println("done; # train sentences: " + trainSentences.size)

    println("Setting word -> category mapping...")
    setCategoryDictionary(trainSentences)
    println("done.")

    val numTrainInstances = trainSentences.foldLeft(0) { _ + _.size }

    indexer = new ml.FeatureIndexer[Feature]
    weights = new WeightVector
    val trainer = getClassifierTrainer(numTrainInstances)
    val tagger = new MaxEntMultiTaggerTrainer(indexer, featureExtractors, trainer, dict)

    tagger.trainWithCache(trainSentences, TrainingOptions.numIters)

    trainer.postProcess // including lazy-updates of all weights
    Problem.removeZeroWeightFeatures(indexer, weights)
    weights.foreach { w => assert(w != 0) }

    save
  }
  override def predict = {
    //loadModel
  }
  override def evaluate = {
    load

    println("Reading CCGBank ...")
    val evalSentences = readSentencesFromCCGBank(developPath, InputOptions.testSize, false)
    val numInstances = evalSentences.foldLeft(0) { _ + _.size }
    println("done; # evaluating sentences: " + evalSentences.size)

    val before = System.currentTimeMillis

    val assignedSentences = superTagToSentences(evalSentences).toArray // evalSentences.map { s => new TrainSentence(s, tagger.candSeq(s, TaggerOptions.beta)) }

    val taggingTime = System.currentTimeMillis - before
    val sentencePerSec = (evalSentences.size.toDouble / (taggingTime / 1000)).formatted("%.1f")
    val wordPerSec = (numInstances.toDouble / (taggingTime / 1000)).formatted("%.1f")

    println("tagging time: " + taggingTime + "ms; " + sentencePerSec + "s/sec; " + wordPerSec + "w/sec")
    evaluateTokenSentenceAccuracy(assignedSentences)
    outputPredictions(assignedSentences)
  }
  def superTagToSentences[S<:TaggedSentence](sentences:Array[S]):ArraySeq[S#AssignedSentence] = {
    // TODO: serialize featureExtractors setting at training
    val tagger = getTagger
    val assignedSentences = sentences.map { s =>
      s.assignCands(tagger.candSeq(s, TaggerOptions.beta, TaggerOptions.maxK))
    }
    assignedSentences
  }
  def getTagger = new MaxEntMultiTagger(indexer, featureExtractors, getClassifier, dict)
  def getClassifier = new ml.ALogLinearClassifier[Int](weights)
  def getClassifierTrainer(numInstances: Int): ml.OnlineLogLinearTrainer[Int] = {
    import OptionEnumTypes.TaggerTrainAlgorithm
    TaggerOptions.taggerTrainAlg match {
      case TaggerTrainAlgorithm.sgd => new ml.LogLinearSGD(weights, TaggerOptions.stepSizeA.toFloat)
      case TaggerTrainAlgorithm.adaGradL1 => new ml.LogLinearAdaGradL1(weights, TaggerOptions.lambda.toFloat, TaggerOptions.eta.toFloat)
      case TaggerTrainAlgorithm.cumulativeL1 =>
        new ml.LogLinearSGDCumulativeL1(weights, TaggerOptions.stepSizeA.toFloat, TaggerOptions.lambda.toFloat, numInstances)
    }
  }

  def evaluateTokenSentenceAccuracy(sentences:Array[TrainSentence]) = {
    var sumUnk = 0
    var correctUnk = 0
    val unkType = dict.unkType
    val (numCorrect, numComplete) = sentences.foldLeft(0, 0) {
      case ((cor, comp), sent) =>
        val numCorrectToken = sent.numCandidatesContainGold
        (cor + numCorrectToken, comp + (if (numCorrectToken == sent.size) 1 else 0))
    }
    val (numUnks, numCorrectUnks): (Int,Int) = sentences.foldLeft(0, 0) {
      case ((sum, cor), sent) =>
        val unkIdxes = (0 until sent.size).filter { sent.word(_) == unkType }
        val unkCorrects = unkIdxes.foldLeft(0) {
          case (n, i) if (sent.cand(i).contains(sent.cat(i))) => n + 1
          case (n, _) => n
        }
        (sum + unkIdxes.size, cor + unkCorrects)
    }
    val numInstances = sentences.foldLeft(0) { _ + _.size }
    println()
    println("token accuracy: " + numCorrect.toDouble / numInstances.toDouble)
    println("sentence accuracy: " + numComplete.toDouble / sentences.size.toDouble)
    println("unknown accuracy: " + numCorrectUnks.toDouble / numUnks.toDouble + " (" + numCorrectUnks + "/" + numUnks + ")")

    val sumCandidates = sentences.foldLeft(0) { case (sum, s) => sum + s.candSeq.map(_.size).sum }
    println("# average of candidate labels after super-tagging: " + (sumCandidates.toDouble / numInstances.toDouble).formatted("%.2f"))
    println()
  }
  override def save = {
    import java.io._
    saveFeaturesToText
    println("saving tagger model to " + OutputOptions.saveModelPath)
    val os = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(OutputOptions.saveModelPath)))
    saveModel(os)
    os.close
  }
  def saveModel(os: ObjectOutputStream) = {
    os.writeObject(dict)
    os.writeObject(indexer)
    os.writeObject(weights)
  }
  def saveFeaturesToText = if (OutputOptions.taggerFeaturePath != "") {
    println("saving features in text to " + OutputOptions.taggerFeaturePath)
    val fw = new FileWriter(OutputOptions.taggerFeaturePath)
    indexer.foreach {
      case (k, v) =>
        val featureString = k match {
          case SuperTaggingFeature(unlabeled, label) => (unlabeled match {
            case unlabeled: FeatureWithoutDictionary => unlabeled.mkString
            case unlabeled: FeatureOnDictionary => unlabeled.mkString(dict)
          }) + "_=>_" + dict.getCategory(label)
        }
        fw.write(featureString + " " + weights(v) + "\n")
    }
    fw.flush
    fw.close
    println("done.")
  }
  def outputPredictions[S<:CandAssignedSentence](sentences:Array[S]) = if (OutputOptions.outputPath != "") {
    println("saving tagger prediction results to " + OutputOptions.outputPath)
    val fw = new FileWriter(OutputOptions.outputPath)
    sentences.foreach { sentence =>
      (0 until sentence.size).foreach { i =>
        fw.write(sentence.word(i) + " " + sentence.cand(i).mkString(" ") + "\n")
      }
      fw.write("\n")
    }
    fw.flush
    fw.close
    println("done.")
  }
  def load = {
    import java.io._
    val in = new ObjectInputStream(new BufferedInputStream(new FileInputStream(InputOptions.loadModelPath)))
    loadModel(in)
    in.close
  }
  def loadModel(in: ObjectInputStream) = {
    dict = in.readObject.asInstanceOf[DictionaryType]
    println("dict load done.")

    indexer = in.readObject.asInstanceOf[ml.FeatureIndexer[Feature]]
    println("tagger feature templates load done.")

    weights = in.readObject.asInstanceOf[WeightVector]
    println("tagger model weights load done.\n")
  }
  def setCategoryDictionary(sentences: Seq[GoldSuperTaggedSentence]): Unit =
    dict.categoryDictionary.resetWithSentences(sentences, DictionaryOptions.unkThreathold)

  def newDictionary: DictionaryType

  // TODO: separate dictionary part into another class
  protected def readSentencesFromCCGBank(path:String, n:Int, train:Boolean): Array[GoldSuperTaggedSentence] = readAndConvertCCGBankTrees(path, n, train, parseTreeConverter.toSentenceFromStringTree _)

  def readParseTreesFromCCGBank(path:String, n:Int, train:Boolean): Array[ParseTree[NodeLabel]] = readAndConvertCCGBankTrees(path, n, train, parseTreeConverter.toLabelTree _)

  private def readAndConvertCCGBankTrees[A:ClassTag](path:String, n:Int, train:Boolean, convert:ParseTree[String]=>A): Array[A] = {
    newCCGBankReader.readParseTrees(path, n, train).map { convert(_) }.toArray
  }

  def newCCGBankReader: CCGBankReader
  def parseTreeConverter: ParseTreeConverter // language specific tree converter
}

class JapaneseSuperTagging extends SuperTagging {
  override type DictionaryType = JapaneseDictionary

  def newDictionary =  new JapaneseDictionary(newCategoryDictionary)

  def newCategoryDictionary = {
    import OptionEnumTypes.CategoryLookUpMethod
    DictionaryOptions.lookupMethod match {
      case CategoryLookUpMethod.surfaceOnly => new Word2CategoryDictionary
      case CategoryLookUpMethod.surfaceAndPoS => new WordPoS2CategoryDictionary
      case CategoryLookUpMethod.surfaceAndSecondFineTag => new WordSecondFineTag2CategoryDictionary
      case CategoryLookUpMethod.surfaceAndSecondWithConj => new WordSecondWithConj2CategoryDictionary
    }
  }
  override def setCategoryDictionary(sentences: Seq[GoldSuperTaggedSentence]): Unit =
    if (DictionaryOptions.useLexiconFiles) setCategoryDictionaryFromLexiconFiles
    else super.setCategoryDictionary(sentences)
  def setCategoryDictionaryFromLexiconFiles = {
    val lexiconPath = pathWithBankDirPathAsDefault(InputOptions.lexiconPath, "Japanese.lexicon")
    val templatePath = pathWithBankDirPathAsDefault(InputOptions.templatePath, "template.lst")
    dict.readLexicon(lexiconPath, templatePath)
  }

  override def newCCGBankReader = new CCGBankReader(dict) // default reader
  override def parseTreeConverter = new JapaneseParseTreeConverter(dict)
}

class EnglishSuperTagging extends SuperTagging {
  override type DictionaryType = SimpleDictionary

  override def featureExtractors = {
    val extractionMethods = Array(new UnigramWordExtractor(5),
      new UnigramPoSExtractor(5),
      new BigramPoSExtractor(5))
    new FeatureExtractorsWithCustomPoSLevel(
      extractionMethods,
      dict.getWord("@@BOS@@"),
      dict.getPoS("@@NULL@@"),
      { pos => pos.id })
  }

  def newDictionary = new SimpleDictionary

  override def newCCGBankReader = new EnglishCCGBankReader(dict)
  override def parseTreeConverter = new EnglishParseTreeConverter(dict)
}
