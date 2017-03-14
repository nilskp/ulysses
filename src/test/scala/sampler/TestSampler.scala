package sampler

import java.io.File
import java.sql.ResultSet
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Random, Success, Try }
import org.junit.{ Before, Test }
import org.junit.AfterClass
import org.junit.Assert._
import sampler.aggr._
import scuff._
import scuff.ddd.Repository
import delta.EventStore
import delta.ddd.{ EntityRepository }
import delta.SysClockTicker
import delta.util.LocalPublishing
import scuff.ddd.DuplicateIdException
import scuff.concurrent.{
  StreamCallback,
  StreamPromise
}
import delta.EventSource
import scala.concurrent.Promise
import delta._
import scuff.reflect.Surgeon
import delta.util.TransientEventStore
import sampler.aggr.emp.EmpEvent
import sampler.aggr.emp.EmpEvent
import sampler.aggr.dept.DeptEvent
import delta.testing.RandomDelayExecutionContext
import scuff.ddd.Revision

class TestSampler {

  def metadata = Map("timestamp" -> new scuff.Timestamp().toString)

  lazy val es: EventStore[Int, DomainEvent, Aggr.Value] =
    new TransientEventStore[Int, DomainEvent, Aggr.Value, JSON](
      RandomDelayExecutionContext) with LocalPublishing[Int, DomainEvent, Aggr.Value] {
      def publishCtx = RandomDelayExecutionContext
    }

  implicit def ec = RandomDelayExecutionContext
  implicit lazy val ticker = LamportTicker(es)

  lazy val EmployeeRepo: Repository[EmpId, Employee] =
    new EntityRepository(Aggr.Empl, Employee.Def)(es)
  lazy val DepartmentRepo: Repository[DeptId, Department] =
    new EntityRepository(Aggr.Dept, Department.Def)(es)

  @Test
  def inserting {
    val id = new EmpId
    assertFalse(EmployeeRepo.exists(id).await.isDefined)
    val register = RegisterEmployee("John Doe", "555-55-5555", new MyDate(1988, 4, 1), 43000, "Janitor")
    val emp = Employee(register)
    val insertRev = EmployeeRepo.insert(id, emp, metadata).await
    assertEquals(0, insertRev)
    Try(EmployeeRepo.insert(id, emp, metadata).await) match {
      case Success(revision) =>
        // Allow idempotent inserts
        assertEquals(0, revision)
      case Failure(th) =>
        fail(s"Should succeed, but didn't: $th")
    }
    emp.apply(UpdateSalary(40000))
    Try(EmployeeRepo.insert(id, emp, metadata).await) match {
      case Success(revision) => fail(s"Should fail, but inserted revision $revision")
      case Failure(th: DuplicateIdException) => // Expected
      case Failure(th) => fail(s"Should have thrown ${classOf[DuplicateIdException].getSimpleName}, not $th")
    }
  }

  @Test
  def updating {
    val id = new EmpId
    assertTrue(EmployeeRepo.exists(id).await.isEmpty)
    val emp = register(id, RegisterEmployee("John Doe", "555-55-5555", new MyDate(1988, 4, 1), 43000, "Janitor"))
    val insertRev = EmployeeRepo.insert(id, emp, metadata).await
    assertEquals(0, insertRev)
    try {
      EmployeeRepo.update(id, Revision(3), metadata) {
        case (emp, revision) =>
          emp(UpdateSalary(45000))
      }.await
      fail("Should throw a Revision.Mismatch")
    } catch {
      case Revision.Mismatch(expected, actual) =>
        assertEquals(3, expected)
        assertEquals(0, actual)
    }
    @volatile var updateRev = -1
    var updatedRev = EmployeeRepo.update(id, Revision(0), metadata) {
      case (emp, revision) =>
        updateRev = revision
        emp(UpdateSalary(45000))
    }.await
    assertEquals(0, updateRev)
    assertEquals(1, updatedRev)
    updatedRev = EmployeeRepo.update(id, Revision(0), metadata) {
      case (emp, revision) =>
        updateRev = revision
        emp(UpdateSalary(45000))
    }.await
    assertEquals(1, updateRev)
    assertEquals(1, updatedRev)
    try {
      EmployeeRepo.update(id, Revision.Exactly(0), metadata) {
        case (emp, revision) =>
          updateRev = revision
          emp(UpdateSalary(66000))
      }.await
      fail("Should throw a Revision.Mismatch")
    } catch {
      case Revision.Mismatch(expected, actual) =>
        assertEquals(0, expected)
        assertEquals(1, actual)
    }
  }

  private def register(id: EmpId, cmd: RegisterEmployee): Employee = {
    val emp = Employee(cmd)
    EmployeeRepo.insert(id, emp, metadata).await
    emp
  }
}
