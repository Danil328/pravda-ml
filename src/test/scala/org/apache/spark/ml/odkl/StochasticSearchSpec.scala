package org.apache.spark.ml.odkl

import java.nio.file.Files

import odkl.analysis.spark.TestEnv
import org.apache.commons.io.FileUtils
import org.apache.spark.ml.odkl.Evaluator.TrainTestEvaluator
import org.apache.spark.ml.odkl.ModelWithSummary.Block
import org.apache.spark.ml.odkl.hyperopt._
import org.scalatest.FlatSpec


/**
  * Created by vyacheslav.baranov on 02/12/15.
  */
class StochasticSearchSpec extends FlatSpec with TestEnv with org.scalatest.Matchers with WithTestData with HasWeights with HasMetricsBlock {
  private lazy val selectedModel = StochasticSearchSpec._selectedModel
  private lazy val gaussianModel = StochasticSearchSpec._gaussianModel  

  "Domain " should " should support reduce" in  {
    val configurations = selectedModel.summary(Block("configurations"))

    val vals = configurations.select("ElasticNet").rdd.map(_.getDouble(0)).collect.sorted

    vals.head should be >= 0.0
    vals.head should be < 0.25
    vals.last should be > 0.25
    vals.last should be <= 0.5
  }

  "Domain " should " should support extend" in {
    val configurations = selectedModel.summary(Block("configurations"))

    val vals = configurations.select("RegParam").rdd.map(_.getDouble(0)).collect.sorted

    vals.head should be >= 0.0
    vals.head should be < 1.0
    vals.last should be > 1.0
    vals.last should be <= 2.0
  }

  "Summary " should " add a model stat" in  {
    val configurations = selectedModel.summary(Block("configurations"))
    configurations.count() should be > 6L
    configurations.count() should be <= 20L

    configurations.schema.size should be(5)

    configurations.schema(0).name should be("configurationIndex")
    configurations.schema(1).name should be("resultingMetric")
    configurations.schema(2).name should be("error")

    configurations.schema(3).name should be("ElasticNet")
    configurations.schema(4).name should be("RegParam")
    
    configurations.show(20, false)
  }

  "Summary " should " add configuration index to metrics" in  {
    val data = selectedModel.summary(metrics)

    data.schema.fieldNames.toSet should contain("configurationIndex")
  }

  "Summary " should " add configuration index to weights" in  {
    val data = selectedModel.summary(weights)

    data.schema.fieldNames.toSet should contain("configurationIndex")
  }

  "Best model " should " should have sound performance " in  {
    val configurations = selectedModel.summary(Block("configurations"))

    configurations.show(100, false)

    configurations.select("resultingMetric").rdd.map(_.getDouble(0)).collect().head should be >= 0.99
  }

  "Best model " should " have index 0" in  {
    val configurations = selectedModel.summary(Block("configurations"))

    configurations.where("configurationIndex = 0").rdd.first().getDouble(1) should be(configurations.rdd.map(_.getDouble(1)).collect.max)
  }

  "Best model " should " have proper weights" in  {
    val data = selectedModel.summary(weights)

    val bestWeights = data
      .where("configurationIndex = 0 AND foldNum = -1")
      .orderBy("name")
      .rdd
      .map(_.getAs[Double]("weight"))
      .collect()

    selectedModel.getCoefficients.toArray should be(bestWeights)
  }
  
  "Gaussian Process " should " find better config " in  {

    val random = selectedModel
      .summary(Block("configurations"))
      .select("resultingMetric").rdd
      .map(_.getDouble(0))
      .collect()
      .sorted

    val gaussian = gaussianModel
      .summary(Block("configurations"))
      .select("resultingMetric").rdd
      .map(_.getDouble(0))
      .collect()
      .sorted

    selectedModel.summary(Block("configurations")).show(100, false)
    gaussianModel.summary(Block("configurations")).show(100, false)

    gaussian.last should be > 0.999

    gaussian.takeRight(3).sum should be >= random.takeRight(3).sum
  }
}

object StochasticSearchSpec extends WithTestData {
  lazy val (_selectedModel, _selectedPath) = fitModel(BayesianParamOptimizer.RANDOM)

  lazy val (_gaussianModel, _gaussianPath) = fitModel(
    BayesianParamOptimizer.GAUSSIAN_PROCESS,
    priorPath = Some(_selectedPath + "/configurations"))

  private def fitModel(mode: BayesianParamOptimizerFactory, numIters: Int = 20, priorPath : Option[String] = None) : (LogisticRegressionModel, String) = {
    val nested = new LogisticRegressionLBFSG()

    val evaluated = Evaluator.crossValidate(
      nested,
      new TrainTestEvaluator(new BinaryClassificationEvaluator()),
      numFolds = 3,
      numThreads = 4)

    val tmpPath = Files.createTempDirectory("models")

    val estimator = new StochasticHyperopt(evaluated)
      .setParamDomains(
        ParamDomainPair(nested.regParam, new DoubleRangeDomain(0, 2)),
        ParamDomainPair(nested.elasticNetParam, new DoubleRangeDomain(0, 0.5)))
      .setMaxIter(numIters)
      .setNanReplacement(0)
      .setSearchMode(mode)
      .setMaxNoImproveIters(6)
      .setTol(0.0005)
      .setTopKForTolerance(4)
      .setEpsilonGreedy(0.1)
      .setPathForTempModels(tmpPath.toString)
      .setMetricsExpression("SELECT AVG(value) FROM __THIS__ WHERE metric = 'auc' AND isTest")
      .setParamNames(
        nested.regParam -> "RegParam",
        nested.elasticNetParam -> "ElasticNet"
      )
      .setNumThreads(2)

    try {
      val model = UnwrappedStage.cacheAndMaterialize(
        priorPath.map(estimator.setPriorsPath).getOrElse(estimator)
      )
        .fit(FeaturesSelectionSpec._withBoth)
      val resultPath = Files.createTempDirectory("result")
      model.write.overwrite().save(resultPath.toString)

      FileUtils.forceDeleteOnExit(resultPath.toFile)

      (model, resultPath.toString)
    } finally {
      FileUtils.deleteDirectory(tmpPath.toFile)
    }
  }
}


