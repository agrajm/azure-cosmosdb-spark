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

import com.microsoft.azure.cosmosdb.spark.config._
import com.microsoft.azure.documentdb._
import com.microsoft.azure.documentdb.internal._

import scala.collection.JavaConversions._
import scala.language.implicitConversions

object CosmosDBConnection {
  // For verification purpose
  var lastConnectionPolicy: ConnectionPolicy = _
  var lastConsistencyLevel: Option[ConsistencyLevel] = _
}

private[spark] case class CosmosDBConnection(config: Config) extends LoggingTrait with Serializable {
  private val databaseName = config.get[String](CosmosDBConfig.Database).get
  private val collectionName = config.get[String](CosmosDBConfig.Collection).get
  private val connectionMode = ConnectionMode.valueOf(config.get[String](CosmosDBConfig.ConnectionMode)
    .getOrElse(CosmosDBConfig.DefaultConnectionMode))
  val collectionLink = s"${Paths.DATABASES_PATH_SEGMENT}/$databaseName/${Paths.COLLECTIONS_PATH_SEGMENT}/$collectionName"

  @transient private var client: DocumentClient = _

  private def documentClient(): DocumentClient = {
    if (client == null) {
      client = accquireClient(connectionMode)

      CosmosDBConnection.lastConnectionPolicy = client.getConnectionPolicy
    }
    client
  }

  private def accquireClient(connectionMode: ConnectionMode): DocumentClient = {
    val connectionPolicy = new ConnectionPolicy()
    connectionPolicy.setConnectionMode(connectionMode)
    connectionPolicy.setUserAgentSuffix(Constants.userAgentSuffix)
    val maxRetryAttemptsOnThrottled = config.get[String](CosmosDBConfig.MaxRetryOnThrottled)
    if (maxRetryAttemptsOnThrottled.isDefined) {
      connectionPolicy.getRetryOptions.setMaxRetryAttemptsOnThrottledRequests(maxRetryAttemptsOnThrottled.get.toInt)
    }
    val maxRetryWaitTimeSecs = config.get[String](CosmosDBConfig.MaxRetryWaitTimeSecs)
    if (maxRetryWaitTimeSecs.isDefined) {
      connectionPolicy.getRetryOptions.setMaxRetryWaitTimeInSeconds(maxRetryWaitTimeSecs.get.toInt)
    }
    val consistencyLevel = ConsistencyLevel.valueOf(config.get[String](CosmosDBConfig.ConsistencyLevel)
      .getOrElse(CosmosDBConfig.DefaultConsistencyLevel))

    val option = config.get[String](CosmosDBConfig.PreferredRegionsList)

    if (option.isDefined) {
      logWarning(s"CosmosDBConnection::Input preferred region list: ${option.get}")
      val preferredLocations = option.get.split(";").toSeq.map(_.trim)
      connectionPolicy.setPreferredLocations(preferredLocations)
    }

    var documentClient = new DocumentClient(
      config.get[String](CosmosDBConfig.Endpoint).get,
      config.get[String](CosmosDBConfig.Masterkey).get,
      connectionPolicy,
      consistencyLevel)

    CosmosDBConnection.lastConsistencyLevel = Some(consistencyLevel)

    documentClient
  }

  def getAllPartitions: Array[PartitionKeyRange] = {
    var ranges = documentClient().readPartitionKeyRanges(collectionLink, null.asInstanceOf[FeedOptions])
    ranges.getQueryIterator.toArray
  }

  def getAllPartitions(query: String): Array[PartitionKeyRange] = {
    var ranges: java.util.Collection[PartitionKeyRange] =
      accquireClient(ConnectionMode.Gateway).readPartitionKeyRanges(collectionLink, query)
    ranges.toArray[PartitionKeyRange](new Array[PartitionKeyRange](ranges.size()))
  }

  def queryDocuments (queryString : String,
        feedOpts : FeedOptions) : Iterator [Document] = {

    documentClient().queryDocuments(collectionLink, new SqlQuerySpec(queryString), feedOpts).getQueryIterable.iterator()
  }

  def readChangeFeed(changeFeedOptions: ChangeFeedOptions): Tuple2[Iterator[Document], String] = {
    val feedResponse = documentClient().queryDocumentChangeFeed(collectionLink, changeFeedOptions)
    Tuple2.apply(feedResponse.getQueryIterable.iterator(), feedResponse.getResponseContinuation)
  }

  def upsertDocument(document: Document,
                     requestOptions: RequestOptions): Document = {
    try {
      logTrace(s"Upserting document $document")
      documentClient().upsertDocument(collectionLink, document, requestOptions, false).getResource
    } catch {
      case e: DocumentClientException =>
        logError("Failed to upsertDocument", e)
        null
    }
  }

  def createDocument(document: Document,
                     requestOptions: RequestOptions): Document = {
    try {
      logTrace(s"Creating document $document")
      documentClient().createDocument(collectionLink, document, requestOptions, false).getResource
    } catch {
      case e: DocumentClientException =>
        logError("Failed to createDocument", e)
        null
    }
  }

  def isDocumentCollectionEmpty: Boolean = {
    logDebug(s"Reading collection $collectionLink")
    var feedOptions: FeedOptions = new FeedOptions
    feedOptions.setPageSize(1)
    if (documentClient().readDocuments(collectionLink, feedOptions).getQueryIterator.hasNext) false else true
  }
}
