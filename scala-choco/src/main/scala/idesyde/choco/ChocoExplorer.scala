package idesyde.choco

import idesyde.identification.choco.ChocoDecisionModel
import java.time.Duration
import forsyde.io.java.core.ForSyDeSystemGraph
import scala.concurrent.ExecutionContext
import idesyde.core.Explorer

import scala.jdk.OptionConverters.*
import scala.jdk.CollectionConverters.*
import scala.jdk.StreamConverters.*
import org.chocosolver.solver.search.limits.SolutionCounter
import org.chocosolver.solver.Solution
import idesyde.core.DecisionModel
import scala.collection.mutable.Buffer
import org.chocosolver.solver.constraints.Constraint
import org.chocosolver.solver.variables.IntVar
import org.chocosolver.solver.search.strategy.Search
import org.chocosolver.solver.search.strategy.strategy.AbstractStrategy
import org.chocosolver.solver.variables.Variable
import org.chocosolver.solver.search.loop.monitors.IMonitorSolution
import idesyde.exploration.choco.explorers.ParetoMinimizationBrancher
import idesyde.core.ExplorationCriteria
import idesyde.utils.Logger
import spire.math.Rational
import idesyde.identification.common.models.mixed.SDFToTiledMultiCore
import idesyde.choco.ChocoExplorableOps._
import idesyde.core.ExplorationCombinationDescription

class ChocoExplorer(using logger: Logger) extends Explorer:

  def combination(decisionModel: DecisionModel): ExplorationCombinationDescription = {
    val canExplore = decisionModel match
      case sdf: SDFToTiledMultiCore => true
      case c: ChocoDecisionModel    => true
      case _                        => false
    ExplorationCombinationDescription(
      canExplore,
      availableCriterias(decisionModel)
        .map(c => c.identifier -> criteriaValue(decisionModel, c))
        .toMap
    )
  }

  override def availableCriterias(decisionModel: DecisionModel): Set[ExplorationCriteria] =
    decisionModel match {
      case cp: ChocoDecisionModel =>
        Set(
          ExplorationCriteria.TimeUntilFeasibility,
          ExplorationCriteria.TimeUntilOptimality,
          ExplorationCriteria.MemoryUntilFeasibility,
          ExplorationCriteria.MemoryUntilOptimality
        )
      case _ => Set()
    }

  override def criteriaValue(
      decisionModel: DecisionModel,
      criteria: ExplorationCriteria
  ): Double = {
    decisionModel match {
      case cp: ChocoDecisionModel => {
        criteria match {
          case ExplorationCriteria.TimeUntilFeasibility =>
            cp.chocoModel.getVars.size * 60
          case ExplorationCriteria.TimeUntilOptimality =>
            cp.chocoModel.getVars.size * 3600
          case ExplorationCriteria.MemoryUntilFeasibility =>
            cp.chocoModel.getVars.size * 10
          case ExplorationCriteria.MemoryUntilOptimality =>
            cp.chocoModel.getVars.size * 1000
          case _ => 0.0
        }
      }
      case _ => 0.0
    }
  }

  private def getLinearizedObj(cpModel: ChocoDecisionModel): IntVar = {
    val normalizedObjs = cpModel.modelMinimizationObjectives.map(o =>
      val scale  = o.getUB() - o.getLB()
      val scaled = cpModel.chocoModel.intVar(s"scaled(${o.getName()})", 0, 100, true)
      scaled.eq(o.sub(o.getLB()).div(scale)).post()
      scaled
    )
    val scalarizedObj = cpModel.chocoModel.intVar("scalarObj", 0, 10000, true)
    cpModel.chocoModel
      .scalar(
        normalizedObjs,
        normalizedObjs.map(o => 100 / normalizedObjs.size),
        "=",
        scalarizedObj
      )
      .post()
    scalarizedObj
  }

  def exploreChocoExplorable[T <: DecisionModel](
      m: T,
      explorationTotalTimeOutInSecs: Long,
      maximumSolutions: Long,
      timeResolution: Long = -1L,
      memoryResolution: Long = -1L
  )(using ChocoExplorable[T]): LazyList[T] = {
    val model  = m.chocoModel(timeResolution, memoryResolution)
    val solver = model.getSolver()
    if (explorationTotalTimeOutInSecs > 0L) {
      logger.debug(s"setting total exploration timeout to ${explorationTotalTimeOutInSecs} seconds")
      solver.limitTime(explorationTotalTimeOutInSecs * 1000L)
    }
    LazyList
      .continually(solver.solve())
      .zipWithIndex
      .takeWhile((feasible, i) =>
        if (maximumSolutions > 0) feasible && i < maximumSolutions else feasible
      )
      .map(_ => solver.defaultSolution().record())
      .map(paretoSolution => m.mergeSolution(paretoSolution, timeResolution, memoryResolution))
  }

  def explore(
      decisionModel: DecisionModel,
      explorationTotalTimeOutInSecs: Long = 0L,
      maximumSolutions: Long = 0L,
      timeDiscretizationFactor: Long = -1L,
      memoryDiscretizationFactor: Long = -1L
  ): LazyList[DecisionModel] = decisionModel match
    case sdf: SDFToTiledMultiCore =>
      exploreChocoExplorable(
        sdf,
        explorationTotalTimeOutInSecs,
        maximumSolutions,
        timeDiscretizationFactor,
        memoryDiscretizationFactor
      )(using CanSolveSDFToTiledMultiCore())
    case solvable: ChocoDecisionModel =>
      val solver          = solvable.chocoModel.getSolver
      val isOptimization  = solvable.modelMinimizationObjectives.size > 0
      val paretoMinimizer = ParetoMinimizationBrancher(solvable.modelMinimizationObjectives)
      // lazy val paretoMaximizer = ParetoMaximizer(
      //   solvable.modelMinimizationObjectives.map(o => solvable.chocoModel.intMinusView(o))
      // )
      // var lastParetoFrontValues = solvable.modelMinimizationObjectives.map(_.getUB())
      // var lastParetoFrontSize = 0
      if (isOptimization) {
        if (solvable.modelMinimizationObjectives.size == 1) {
          solvable.chocoModel.setObjective(
            false,
            solvable.modelMinimizationObjectives.head
          )
        }
        solver.plugMonitor(paretoMinimizer)
        solvable.chocoModel.post(new Constraint("paretoOptConstraint", paretoMinimizer))
        // val objFunc = getLinearizedObj(solvable)
        // solvable.chocoModel.setObjective(false, objFunc)
        // strategies +:= Search.bestBound(Search.minDomLBSearch(objFunc))
      }
      // solver.addStopCriterion(SolutionCounter(solvable.chocoModel, 2L))
      if (!solvable.strategies.isEmpty) {
        solver.setSearch(solvable.strategies: _*)
      }
      if (solvable.shouldLearnSignedClauses) {
        solver.setLearningSignedClauses
      }
      if (solvable.shouldRestartOnSolution) {
        solver.setNoGoodRecordingFromRestarts
        solver.setRestartOnSolutions
      }
      if (explorationTotalTimeOutInSecs > 0L) {
        logger.debug(
          s"setting total exploration timeout to ${explorationTotalTimeOutInSecs} seconds"
        )
        solver.limitTime(explorationTotalTimeOutInSecs * 1000L)
      }
      LazyList
        .continually(solver.solve())
        // .scanLeft(
        //   (true, 0, chocoCpModel.modelMinimizationObjectives.map(_.getUB()))
        // )((accum, feasible) => {
        //   var (paretoFrontChanged, lastParetoFrontSize, lastParetoFrontValues) = accum
        //   if (isOptimization) {
        //     paretoFrontChanged = false
        //     if (lastParetoFrontSize != paretoMaximizer.getParetoFront().size()) {
        //       lastParetoFrontSize = paretoMaximizer.getParetoFront().size()
        //       paretoFrontChanged = true
        //     } else {
        //       paretoMaximizer
        //         .getParetoFront()
        //         .forEach(s => {
        //           val isDominator =
        //             chocoCpModel.modelMinimizationObjectives.zipWithIndex.forall((o, i) => {
        //               s.getIntVal(o) < lastParetoFrontValues(i)
        //             })
        //           if (isDominator) {
        //             lastParetoFrontValues =
        //               chocoCpModel.modelMinimizationObjectives.map(o => s.getIntVal(o))
        //             paretoFrontChanged = true
        //           }
        //         })
        //     }
        //   }
        //   // println("the values " + (feasible && paretoFrontChanged, lastParetoFrontSize, lastParetoFrontValues.mkString(", ")))
        //   (feasible && paretoFrontChanged, lastParetoFrontSize, lastParetoFrontValues)
        // })
        // .takeWhile((shouldContinue, _, _) => shouldContinue)
        .takeWhile(feasible => feasible)
        // .filter(feasible => feasible)
        .map(_ => {
          // scribe.debug(s"Current heap memory used: ${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} bytes")
          solver.defaultSolution()
        })
        .flatMap(paretoSolution => {
          // println("obj " + chocoCpModel.modelMinimizationObjectives.map(o => paretoSolution.getIntVal(o)).mkString(", "))
          solvable.rebuildFromChocoOutput(paretoSolution)
        })
    case _ => LazyList.empty

  def uniqueIdentifier: String = "ChocoExplorer"

end ChocoExplorer