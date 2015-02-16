package twitter.stream

import com.datastax.spark.connector.streaming._
import com.datastax.spark.connector.SomeColumns
import org.apache.spark.streaming.dstream.{DStream, ReceiverInputDStream}
import org.apache.spark.streaming.{Time, StreamingContext, Seconds}
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.twitter.TwitterUtils
import org.joda.time.{DateTime, DateTimeZone}
import twitter.stream.StreamingSchema.{KEYSPACE, TABLE}
import twitter4j.Status
import twitter4j.auth.OAuthAuthorization
import twitter4j.conf.ConfigurationBuilder

object TwitterStreaming {

 /*
  * Pass in Twitter properties as -D system properties:
  * -Dtwitter4j.oauth.consumerKey="value"
  * -Dtwitter4j.oauth.consumerSecret="value"
  * -Dtwitter4j.oauth.accessToken="value"
  * -Dtwitter4j.oauth.accessTokenSecret="value"
  */

  val StreamingBatchInterval = 5

  val Keywords = Seq("love","you","hate","cat","lol")
  
  def main (args: Array[String]): Unit = {


    val consumerKey: String = sys.env.getOrElse("TWITTER_CONSUMER_KEY", sys.props("twitter4j.oauth.consumerKey"))
    val consumerSecret: String = sys.env.getOrElse("TWITTER_CONSUMER_SECRET", sys.props("twitter4j.oauth.consumerSecret"))

     val accessToken: String = sys.env.getOrElse("TWITTER_ACCESS_TOKEN", sys.props("twitter4j.oauth.accessToken"))

     val accessTokenSecret: String = sys.env.getOrElse("TWITTER_ACCESS_TOKEN_SECRET", sys.props("twitter4j.oauth.accessTokenSecret"))

     val authorization: OAuthAuthorization = new OAuthAuthorization(new ConfigurationBuilder()
       .setOAuthConsumerKey(consumerKey)
       .setOAuthConsumerSecret(consumerSecret)
       .setOAuthAccessToken(accessToken)
       .setOAuthAccessTokenSecret(accessTokenSecret)
       .build)

     val conf = new SparkConf(true)
       .setAppName("stream_to_cassandra")
       .setMaster("local[4]")
       .set("spark.cassandra.connection.host", "localhost")

     StreamingSchema.prepareSchemaAndCleanData(conf)

     val sc = new SparkContext(conf)
     val ssc = new StreamingContext(sc, Seconds(StreamingBatchInterval))

     val stream: ReceiverInputDStream[Status] = TwitterUtils.createStream(ssc, Some(authorization), Nil, StorageLevel.MEMORY_ONLY_SER_2)

     stream.flatMap(_.getText.toLowerCase.split("""\s+"""))
       .filter(Keywords.contains(_))
       .countByValueAndWindow(Seconds(5), Seconds(5))
       .transform((rdd, time) => rdd.map { case (term, count) => (term, count, now(time))})
       .saveToCassandra(KEYSPACE, TABLE, SomeColumns("topic", "mentions", "interval"))

     ssc.checkpoint("/tmp/checkpoint")
     ssc.start()
     ssc.awaitTermination()

   }

  private def now(time: Time): String =
    new DateTime(time.milliseconds, DateTimeZone.UTC).toString("yyyyMMddHH:mm:ss.SSS")
}