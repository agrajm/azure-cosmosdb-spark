/**
  * The MIT License (MIT)
  * Copyright (c) 2016 Microsoft Corporation
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  */
package com.microsoft.azure.cosmosdb.spark

import com.microsoft.azure.documentdb.internal.directconnectivity.HttpClientFactory
import com.microsoft.azure.cosmosdb.spark.config.{Config, CosmosDBConfig}
import org.apache.commons.lang.StringUtils
import org.apache.spark.sql.SparkSession
import org.apache.spark.{SparkConf, SparkContext}
import org.scalatest._

trait RequiresCosmosDB extends FlatSpec with Matchers with BeforeAndAfterAll with BeforeAndAfterEach with LoggingTrait {

  val cosmosDBDefaults: CosmosDBDefaults = CosmosDBDefaults()
  private var _currentTestName: Option[String] = None

  private def sparkContext: SparkContext = _sparkContext

  private lazy val _sparkContext: SparkContext = {
    _currentTestName = Some(suiteName)
    new SparkContext(sparkConf)
  }

  private val collName: String = _currentTestName.getOrElse(suiteName).filter(_.isLetterOrDigit)

  /**
    * The collection name to use for this test
    */
  def collectionName: String = collName

  def sparkConf: SparkConf = sparkConf(collectionName)

  def sparkConf(collectionName: String): SparkConf = cosmosDBDefaults.getSparkConf(collectionName)

  /**
    * Test against a set SparkContext
    *
    * @param testCode the test case
    */
  def withSparkContext()(testCode: SparkContext => Any) {
    try {
      logInfo(s"Running Test: '${_currentTestName.getOrElse(suiteName)}'")
      testCode(sparkContext)
    } finally {
    }
  }

  /**
    * Test against a set Spark Session
    *
    * @param testCode the test case
    */
  def withSparkSession()(testCode: SparkSession => Any) {
    try {
      logInfo(s"Running Test: '${_currentTestName.getOrElse(suiteName)}'")
      testCode(createOrGetDefaultSparkSession(sparkContext)) // "loan" the fixture to the test
    } finally {
    }
  }

  override def beforeAll(): Unit = {
    // if running against localhost emulator
    HttpClientFactory.DISABLE_HOST_NAME_VERIFICATION = true

    val config: Config = Config(sparkConf)
    val databaseName: String = config.get(CosmosDBConfig.Database).get
    cosmosDBDefaults.deleteDatabase(databaseName)
    cosmosDBDefaults.createDatabase(databaseName)
  }

  override def beforeEach(): Unit = {
    val config: Config = Config(sparkConf)
    val databaseName: String = config.get(CosmosDBConfig.Database).get
    val collectionName: String = config.get(CosmosDBConfig.Collection).get
    cosmosDBDefaults.createCollection(databaseName, collectionName)
  }

  override def afterEach(): Unit = {
    val config: Config = Config(sparkConf)
    val databaseName: String = config.get(CosmosDBConfig.Database).get
    val collectionName: String = config.get(CosmosDBConfig.Collection).get
    cosmosDBDefaults.deleteCollection(databaseName, collectionName)
  }

  def createOrGetDefaultSparkSession(sc: SparkContext): SparkSession = {
    var builder = SparkSession.builder().config(sc.getConf)
    val osName = System.getProperty("os.name")
    if (!StringUtils.isEmpty(osName) && osName.toLowerCase().contains("win")) {
      // The spark.sql.warehouse.dir parameter is to workaround an path issue with Spark on Windows
      builder.config("spark.sql.warehouse.dir", s"file:///${System.getProperty("user.dir")}")
    }
    builder.getOrCreate()
  }
}
