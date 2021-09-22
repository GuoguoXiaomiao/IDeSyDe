package idesyde.exploration

import idesyde.identification.models.reactor.ReactorMinusJobsMapAndSched
import idesyde.identification.DecisionModel
import scala.concurrent.Future
import forsyde.io.java.core.ForSyDeModel
import scala.concurrent.ExecutionContext
import java.time.Duration
import java.time.temporal.Temporal

final case class ReactorMinusToNetHWOrToolsExplorer() extends Explorer:

  def canExplore(decisionModel: DecisionModel) = false

  def explore(decisionModel: DecisionModel)(using
      ExecutionContext
  ) =
    Future(Option(ForSyDeModel()))

  def estimateTimeUntilFeasibility(decisionModel: DecisionModel): Duration =
    decisionModel match
      case m: ReactorMinusJobsMapAndSched =>
        Duration.ofSeconds(
          m.reactorMinus.jobGraph.jobs.size * m.reactorMinus.jobGraph.channels.size
        )
      case _ => Duration.ZERO

  def estimateTimeUntilOptimality(decisionModel: DecisionModel): Duration =
    decisionModel match
      case m: ReactorMinusJobsMapAndSched =>
        Duration.ofMinutes(
          m.reactorMinus.jobGraph.jobs.size * m.reactorMinus.jobGraph.channels.size * m.platform.coveredVertexes.size
        )
      case _ => Duration.ZERO

  def estimateMemoryUntilFeasibility(decisionModel: DecisionModel): Long =
    decisionModel match
      case m: ReactorMinusJobsMapAndSched =>
        256 * m.reactorMinus.jobGraph.jobs.size * m.reactorMinus.jobGraph.channels.size
      case _ => 0

  def estimateMemoryUntilOptimality(decisionModel: DecisionModel): Long =
    decisionModel match
      case m: ReactorMinusJobsMapAndSched =>
        100 * estimateMemoryUntilFeasibility(decisionModel)
      case _ => 0
