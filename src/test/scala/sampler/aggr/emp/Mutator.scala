package sampler.aggr.emp

import sampler._
import ulysses.ddd.StateMutator

case class State(
  name: String,
  soch: String,
  dob: MyDate,
  salary: Int,
  title: String)

class Mutator(var state: State = null)
    extends EmpEventHandler
    with StateMutator[EmpEvent, State] {

  def this(state: Option[State]) = this(state.orNull)

  type RT = Unit

  protected def process(evt: EmpEvent) = dispatch(evt)

  /** Genesis event. */
  def on(evt: EmployeeRegistered): RT = {
    require(state == null)
    state = State(name = evt.name, soch = evt.soch, dob = evt.dob, salary = evt.annualSalary, title = evt.title)
  }

  def on(evt: EmployeeSalaryChange): RT = {
    state = state.copy(salary = evt.newSalary)
  }

  def on(evt: EmployeeTitleChange): RT = {
    state = state.copy(title = evt.newTitle)
  }

}
