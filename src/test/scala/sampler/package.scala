
import sampler.aggr.DomainEvent
import scuff.serialVersionUID
import delta.EventCodec
import delta.util.ReflectiveDecoder
import sampler.aggr.Employee
import sampler.aggr.Department
import scala.util.Random
import scala.concurrent._, duration._

import language.implicitConversions

package sampler {
  case class Id[T](int: Int = Random.nextInt)

  trait AbstractEventCodec[SF]
    extends EventCodec[DomainEvent, SF] {

    def nameOf(cls: EventClass): String = {
      val fullName = cls.getName
      val sepIdx = fullName.lastIndexOf('.', fullName.lastIndexOf('.') - 1)
      fullName.substring(sepIdx + 1)
    }
    def versionOf(cls: EventClass): Byte = serialVersionUID(cls).toByte

  }

}

package object sampler {

  val isDebug = java.lang.management.ManagementFactory
    .getRuntimeMXBean
    .getInputArguments
    .toString.contains("jdwp")

  val AwaitDuration = if (isDebug) 60.hours else 60.seconds

  implicit class F[T](f: Future[T]) {
    def await = Await.result(f, AwaitDuration)
  }

  type JSON = String

  implicit def toFuture[T](t: T): Future[T] = Future successful t

  type DeptId = Id[Department]
  type EmpId = Id[Employee]

  implicit def id2int(id: Id[_]) = id.int

  implicit object JsonDomainEventCodec
      extends ReflectiveDecoder[DomainEvent, JSON]
      with AbstractEventCodec[JSON]
      with aggr.emp.JsonCodec
      with aggr.dept.JsonCodec {

    override type Return = JSON

    def encode(evt: DomainEvent) = evt match {
      case evt: aggr.dept.DeptEvent => evt.dispatch(this)
      case evt: aggr.emp.EmpEvent => evt.dispatch(this)
    }
  }

}
