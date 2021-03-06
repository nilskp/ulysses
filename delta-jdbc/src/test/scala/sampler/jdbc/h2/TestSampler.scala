package sampler.jdbc.h2

import java.io.File

import org.h2.jdbcx.JdbcDataSource
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Test

import sampler.{ JSON, JsonDomainEventFormat }
import sampler.aggr.DomainEvent

import delta.jdbc._
import delta.jdbc.h2._
import delta.util.LocalTransport
import delta.testing.RandomDelayExecutionContext
import delta.MessageTransportPublishing

import scala.util.Random

import scuff.jdbc._
import scala.concurrent.ExecutionContext

object TestSampler {
  val h2Name = s"delete-me.h2db.${Random.nextInt().abs}"
  val h2File = new File(".", h2Name + ".mv.db")
  @AfterClass
  def cleanup(): Unit = {
    h2File.delete()
  }
  implicit object StringColumn extends VarCharColumn
}

final class TestSampler extends sampler.TestSampler {

  import TestSampler._

  override lazy val es = {
    val sql = new H2Dialect[Int, DomainEvent, JSON](None)
    val cs = new AsyncConnectionSource with DataSourceConnection {

      override def updateContext: ExecutionContext = RandomDelayExecutionContext

      override def queryContext: ExecutionContext = RandomDelayExecutionContext

      val dataSource = new JdbcDataSource
      dataSource.setURL(s"jdbc:h2:./${h2Name}")
    }
    new JdbcEventStore(JsonDomainEventFormat, sql, cs)(initTicker)
      with MessageTransportPublishing[Int, DomainEvent] {
      def toTopic(ch: Channel) = Topic(ch.toString)
      val txTransport = new LocalTransport[Transaction](t => toTopic(t.channel), RandomDelayExecutionContext)
      val txChannels = Set(college.semester.Semester.channel, college.student.Student.channel)
      val txCodec = scuff.Codec.noop[Transaction]
    }.ensureSchema()
  }

  @Test
  def mock(): Unit = {
    assertTrue(true)
  }
}
