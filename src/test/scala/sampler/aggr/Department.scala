package sampler.aggr

import collection.immutable.Seq
import delta.ddd._
import delta.ddd._

import sampler._
import sampler.aggr.dept._
import scala.concurrent.Future

/** Genesis command. */
case class CreateDepartment(name: String)
case class AddEmployee(id: EmpId)
case class RemoveEmployee(id: EmpId)

trait Department {
  def apply(cmd: AddEmployee): this.type
  def apply(cmd: RemoveEmployee): this.type
}

object Department {
  type State = delta.ddd.State[DeptState, DeptEvent]

  def insert(repo: Repository[DeptId, Department])(
    id: DeptId, cmd: CreateDepartment)(
      thunk: Department => Map[String, String]): Future[Int] = {
    val name = cmd.name.trim()
    require(name.length() > 0)
    val dept = new Impl
    dept.state(DeptCreated(name))
    val metadata = thunk(dept)
    repo.insert(id, dept, metadata)
  }

  object Def extends Entity[Department, DeptState, DeptEvent](DeptAssembler) {
    type Id = DeptId
    def init(state: State, mergeEvents: List[DeptEvent]) = new Impl(state, mergeEvents)
    def state(dept: Department) = dept match {
      case dept: Impl => dept.state
    }
    def validate(state: DeptState) = require(state != null)
  }

  private[aggr] class Impl(val state: State = Def.newState(), mergeEvents: Seq[DeptEvent] = Nil)
      extends Department {
    @inline
    private def dept = state.curr

    def apply(cmd: AddEmployee) = {
      if (!dept.employees(cmd.id)) {
        state(EmployeeAdded(cmd.id))
      }
      this
    }
    def apply(cmd: RemoveEmployee) = {
      if (dept.employees(cmd.id)) {
        state(EmployeeRemoved(cmd.id))
      }
      this
    }

  }

}
