/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.sumologic.elasticsearch.restlastic

import com.sumologic.elasticsearch.restlastic.RestlasticSearchClient.ReturnTypes.IndexAlreadyExistsException
import spray.http.HttpMethods._
import com.sumologic.elasticsearch.restlastic.dsl.Dsl._
import com.sumologic.elasticsearch_test.ElasticsearchIntegrationTest
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class RestlasticSearchClientTest extends WordSpec with Matchers with ScalaFutures with BeforeAndAfterAll with ElasticsearchIntegrationTest {
  val index = Index(IndexName)
  val tpe = Type("foo")
  val analyzerName = Name("keyword_lowercase")

  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val patience = PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(50, Millis)))

  lazy val restClient = {
    val (host, port) = endpoint
    new RestlasticSearchClient(new StaticEndpoint(new Endpoint(host, port)))
  }

  "RestlasticSearchClient" should {
    "Be able to create an index and setup index setting" in {
      val analyzer = Analyzer(analyzerName, Keyword, Lowercase)
      val indexSetting = IndexSetting(12, 1, analyzer)
      val indexFut = restClient.createIndex(index, Some(indexSetting))
      whenReady(indexFut) { _ => refresh() }
    }

    "Be able to setup document mapping" in {
      val basicFiledMapping = BasicFieldMapping(StringType, None, Some(analyzerName))
      val timestampMapping = EnabledFieldMapping(true)
      val metadataMapping = Mapping(tpe, IndexMapping(
        Map("name" -> basicFiledMapping, "f1" -> basicFiledMapping, "suggest" -> CompletionMapping(Map("f" -> CompletionContext("name")), analyzerName)),
        timestampMapping))

      val mappingFut = restClient.putMapping(index, tpe, metadataMapping)
      whenReady(mappingFut) { _ => refresh() }
    }

    "Be able to create an index, index a document, and search it" in {
      val ir = for {
        ir <- restClient.index(index, tpe, Document("doc1", Map("text" -> "here")))
      } yield {
          ir
      }
      whenReady(ir) { ir =>
        ir.created should be(true)
      }
      refresh()
      val resFut = restClient.query(index, tpe, QueryRoot(TermQuery("text", "here")))
      whenReady(resFut) { res =>
        res.sourceAsMap.toList should be(List(Map("text" -> "here")))
      }
    }

    "Throw IndexAlreadyExists exception" in {
      val res = for {
        _ <- restClient.createIndex(index)
        _ <- restClient.createIndex(index)
      } yield {
          "created"
        }
      intercept[IndexAlreadyExistsException] {
        Await.result(res, 10.seconds)
      }
    }

    "Support document mapping" in {
      val doc = Document("doc6", Map("f1" -> "f1value", "f2" -> 5))
      val fut = restClient.index(index, tpe, doc)
      whenReady(fut) { _ => refresh() }
      val resFut = restClient.query(index, tpe, QueryRoot(TermQuery("f1", "f1value")))
      whenReady(resFut) { resp =>
        resp.extractSource[DocType].head should be(DocType("f1value", 5))
      }
    }

    "Support bulk indexing" in {

      val doc3 = Document("doc3", Map("text" -> "here"))
      val doc4 = Document("doc4", Map("text" -> "here"))
      val doc5 = Document("doc5", Map("text" -> "nowhere"))

      // doc3 is inserted twice, so when it is inserted in bulk, it should have already been created
      val fut = for {
        _ <- restClient.index(index, tpe, doc3)
        bulk <- restClient.bulkIndex(index, tpe, Seq(doc3, doc4, doc5))
      } yield {
        bulk
      }
      whenReady(fut) { resp =>
        resp.length should be(3)
        resp(0).created should be(false)
        resp(0).alreadyExists should be(true)
        resp(1).created should be(true)
        resp(2).created should be(true)
      }

      refresh()
      val resFut = restClient.query(index, tpe, QueryRoot(TermQuery("text", "here")))
      whenReady(resFut) { res =>
        res.jsonStr should include("doc3")
        res.jsonStr should include("doc4")
        res.jsonStr should not include("doc5")
      }
    }

    "Support scroll requests" in {
      val fut = restClient.startScrollRequest(index, tpe, QueryRoot(MatchAll, Some(1)))
      val scrollId = whenReady(fut) { resp =>
        resp.id should not be('empty)
        resp
      }
      whenReady(restClient.scroll(scrollId)) { resp =>
        resp._2.sourceAsMap should not be ('empty)
        resp._2.sourceAsMap.head should not be ('empty)
      }
    }

    "Support the count API" in {
      val docFutures = (1 to 10).map { n =>
        Document(s"doc-$n", Map("ct" -> "ct", "id" -> n))
      }.map { doc =>
        restClient.index(index, tpe, doc)
      }

      val docs = Future.sequence(docFutures)
      whenReady(docs) { _ =>
        refresh()
      }
      val ctFut = restClient.count(index, tpe, QueryRoot(TermQuery("ct", "ct")))
      whenReady(ctFut) { ct =>
        ct should be (10)
      }
    }

    "Support raw requests" in {
      val future = restClient.runRawEsRequest(op = "", endpoint = "/_stats/indices", GET)
      whenReady(future) { res =>
        res.jsonStr should include(IndexName)
      }
    }

    "Support delete documents" in {
      val ir = restClient.index(index, tpe, Document("doc7", Map("text7" -> "here7")))
      whenReady(ir) { ir =>
        ir.created should be(true)
      }
      refresh()
      val resFut = restClient.query(index, tpe, QueryRoot(TermQuery("text7", "here7")))
      whenReady(resFut) { res =>
        res.sourceAsMap.toList should be(List(Map("text7" -> "here7")))
      }
      val delFut = restClient.deleteDocument(index, tpe, QueryRoot(TermQuery("text7", "here7")))
      Await.result(delFut, 10.seconds)
      val resFut1 = restClient.query(index, tpe, QueryRoot(TermQuery("text7", "here7")))
      whenReady(resFut1) { res =>
        res.sourceAsMap.toList should be(List())
      }
    }

    "Support bulk update document when document does not exist" in {
      val doc1 = Document("bulk_doc1", Map("text" -> "here"))
      val doc2 = Document("bulk_doc2", Map("text" -> "here"))
      val doc3 = Document("bulk_doc3", Map("text" -> "here"))

      val fut = for {
        bulk <- restClient.bulkUpdate(index, tpe, Seq(doc1, doc2, doc3))
      } yield {
        bulk
      }

      whenReady(fut) { resp =>
        resp.length should be(3)
        resp(0).created should be(true)
        resp(1).created should be(true)
        resp(2).created should be(true)
      }

      refresh()
      val resFut = restClient.query(index, tpe, QueryRoot(TermQuery("text", "here")))
      whenReady(resFut) { res =>
        res.jsonStr should include("bulk_doc1")
        res.jsonStr should include("bulk_doc2")
        res.jsonStr should include("bulk_doc3")
      }
    }

    "Support bulk update document when document exists with different content" in {
      val doc1 = Document("bulk_doc1", Map("text" -> "updated"))
      val doc2 = Document("bulk_doc2", Map("text" -> "updated"))
      val doc3 = Document("bulk_doc3", Map("text" -> "updated"))

      val fut = for {
        bulk <- restClient.bulkUpdate(index, tpe, Seq(doc1, doc2, doc3))
      } yield {
        bulk
      }

      whenReady(fut) { resp =>
        resp.length should be(3)
        resp(0).created should be(false)
        resp(1).created should be(false)
        resp(2).created should be(false)
      }

      refresh()
      val resFut = restClient.query(index, tpe, QueryRoot(TermQuery("text", "updated")))
      whenReady(resFut) { res =>
        res.jsonStr should include("bulk_doc1")
        res.jsonStr should include("bulk_doc2")
        res.jsonStr should include("bulk_doc3")
      }
    }

    "Support case insensitive autocomplete" in {

      val keyWords = List("Case", "case", "#Case`case")
      val input = Map(
        "name" -> "test",
        "suggest" -> Map(
          "input" -> keyWords
        )
      )
      val docFut = restClient.index(index, tpe, Document("autocompelte", input))
      whenReady(docFut) { _ => refresh() }

      // test lower case c
      val autocompleteLower = restClient.suggest(index, tpe, Suggest("c", Completion("suggest", 50, Map("f" -> "test"))))
      whenReady(autocompleteLower) {
        resp => resp should be(List("Case", "case"))
      }
      // test upper case C
      val autocompleteUpper = restClient.suggest(index, tpe, Suggest("C", Completion("suggest", 50, Map("f" -> "test"))))
      whenReady(autocompleteUpper) {
        resp => resp should be(List("Case", "case"))
      }
      // test special characters
      val autocompleteSpecial = restClient.suggest(index, tpe, Suggest("#", Completion("suggest", 50, Map("f" -> "test"))))
      whenReady(autocompleteSpecial) {
        resp => resp should be(List("#Case`case"))
      }
    }

    "Suport case insensitive query" in {
      val docLower = Document("caseinsensitivequerylower", Map("f1" -> "CaSe", "f2" -> 5))
      val futLower = restClient.index(index, tpe, docLower)
      whenReady(futLower) { _ => refresh() }
      // WildcardQuery is not analyzed https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-wildcard-query.html
      val resFutLower = restClient.query(index, tpe, QueryRoot(WildcardQuery("f1", "case")))
      whenReady(resFutLower) { resp =>
        resp.extractSource[DocType].head should be(DocType("CaSe", 5))
      }
    }

    "Support range queries" in {
      val rangeFutures = (1 to 10).map { n =>
        Document(s"range-$n", Map("range-id" -> n))
      }.map { doc =>
        restClient.index(index, tpe, doc)
      }

      val range = Future.sequence(rangeFutures)
      whenReady(range) { _ =>
        refresh()
      }

      val ltQuery = QueryRoot(RangeQuery("range-id", Lt("4")))
      val ltFut = restClient.query(index, tpe, ltQuery)
      whenReady(ltFut) { resp =>
        resp should have length 3
      }

      val lteQuery = QueryRoot(RangeQuery("range-id", Lte("4")))
      val lteFut = restClient.query(index, tpe, lteQuery)
      whenReady(lteFut) { resp =>
        resp should have length 4
      }

      val gtQuery = QueryRoot(RangeQuery("range-id", Gt("4")))
      val gtFut = restClient.query(index, tpe, gtQuery)
      whenReady(gtFut) { resp =>
        resp should have length 6
      }

      val gteQuery = QueryRoot(RangeQuery("range-id", Gte("4")))
      val gteFut = restClient.query(index, tpe, gteQuery)
      whenReady(gteFut) { resp =>
        resp should have length 7
      }

      val sliceQuery = QueryRoot(RangeQuery("range-id", Gte("5"), Lte("6")))
      val sliceFut = restClient.query(index, tpe, sliceQuery)
      whenReady(sliceFut) { resp =>
        resp should have length 2
      }
    }
    "Support multi-term filtered query" in {
      val ir = for {
        ir <- restClient.index(index, tpe, Document("multi-term-query-doc",
          Map("filter1" -> "val1", "filter2"-> "val2")))
      } yield {
        ir
      }
      whenReady(ir) { ir =>
        ir.created should be(true)
      }
      refresh()
      val termf1 = TermFilter("filter1", "val1")
      val termf2 = TermFilter("filter2", "val2")
      val termf3 = TermFilter("filter1", "val2")
      val validQuery = MultiTermFilteredQuery(MatchAll, termf1, termf2)
      val invalidQuery = MultiTermFilteredQuery(MatchAll, termf1, termf3)
      val invalidQuery2 = MultiTermFilteredQuery(MatchAll, termf3, termf1)
      val resFut = restClient.query(index, tpe, QueryRoot(validQuery))
      whenReady(resFut) { res =>
        res.sourceAsMap.toList should be(List(Map("filter1" -> "val1", "filter2" -> "val2")))
      }
      val resFut2 = restClient.query(index, tpe, QueryRoot(invalidQuery))
      whenReady(resFut2) { res =>
        res.sourceAsMap.toList should be(List())
      }
      val resFut3 = restClient.query(index, tpe, QueryRoot(invalidQuery2))
      whenReady(resFut3) { res =>
        res.sourceAsMap.toList should be(List())
      }
    }
  }
}


case class DocType(f1: String, f2: Int)
