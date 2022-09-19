package idesyde.identification.choco.models.sdf

import org.chocosolver.solver.variables.IntVar
import org.chocosolver.solver.search.strategy.strategy.AbstractStrategy
import org.chocosolver.solver.search.strategy.decision.IntDecision
import org.chocosolver.util.PoolManager
import org.chocosolver.solver.search.strategy.decision.Decision
import breeze.linalg._
import org.chocosolver.solver.search.strategy.assignments.DecisionOperatorFactory
import scala.util.Random
import org.chocosolver.solver.search.loop.monitors.IMonitorSolution
import idesyde.utils.CoreUtils.wfor
import idesyde.utils.CoreUtils
import idesyde.identification.forsyde.models.sdf.SDFApplication

class CommAwareMultiCoreSDFListScheduling(
    val sdfApplications: SDFApplication,
    val actorDuration: Array[Array[Int]],
    val channelsTravelTime: Array[Array[Array[IntVar]]],
    val firingsInSlots: Array[Array[Array[IntVar]]],
    val invThroughputs: Array[IntVar],
    val globalInvThroughput: IntVar
) extends AbstractStrategy[IntVar]((firingsInSlots.flatten.flatten): _*)
    with IMonitorSolution {

  private val recomputeMethods = SDFChocoRecomputeMethods(firingsInSlots)

  private val numActors     = firingsInSlots.size
  private val numSchedulers = firingsInSlots.head.size
  private val numSlots      = firingsInSlots.head.head.size
  private val numChannels   = channelsTravelTime.size
  var channels              = (0 until sdfApplications.initialTokens.size).toArray
  var actors                = (0 until sdfApplications.sdfRepetitionVectors.size).toArray
  var schedulers            = (0 until firingsInSlots.head.size).toArray
  val slots                 = (0 until firingsInSlots.head.head.size).toArray

  private val mat     = DenseMatrix(sdfApplications.sdfBalanceMatrix: _*)
  private val consMat = mat.map(v => if (v < 0) then v else 0)
  private val prodMat = mat.map(v => if (v > 0) then v else 0)

  // val firingVector: Array[SparseVector[Int]] = slots.map(slot => SparseVector.zeros[Int](actors.size)).toArray

  // var parallelism = 0

  val singleActorFire =
    actors
      .map(a =>
        (0 to sdfApplications
          .sdfRepetitionVectors(a)).map(q => recomputeMethods.mkSingleActorFire(a)(q)).toArray
      )
      .toArray

  val pool = PoolManager[IntDecision]()

  var tokenVec     = DenseVector(sdfApplications.initialTokens)
  var accumFirings = DenseVector.zeros[Int](actors.size)

  val follows = actors.map(src =>
    actors.map(dst => {
      channels.exists(c => mat(c, src) > 0 && mat(c, dst) < 0)
    })
  )

  val followsMatrix = CSCMatrix(
    follows: _*
  )

  val followsClosure = CoreUtils.reachibilityClosure(follows)

  /** This function checks whether the firing schedule can induce a self-contention between actors
    * of the same sub-application, i.e. that are connected. The logic it uses is to use the provided
    * scheduler ordering an always suggest to map actors in a feed-forward manner.
    *
    * @param actor
    *   @param scheduler
    * @param slot
    *   @return whether firing the actor at this scheduler and slot can produce a self-contention
    */
  def canBuildSelfLoop(actor: Int)(scheduler: Int)(slot: Int): Boolean = {
    wfor(0, _ < numActors, _ + 1) { a =>
      if (a != actor && followsClosure(a)(actor)) {
        // start at scheduler to check if the predecessor 'a' is already part of the feed-forward path
        wfor(0, _ < slot, _ + 1) { s =>
          wfor(scheduler, _ < numSchedulers, _ + 1) { p =>
            if (p != scheduler) {
              if (firingsInSlots(a)(p)(s).isInstantiated() && firingsInSlots(a)(p)(s).getLB() > 0) {
                return true
              }
            }
          }
        }
      }
    }
    return false
  }

  def getDecision(): Decision[IntVar] = {
    // val schedulersShuffled = Random.shuffle(schedulers)
    // normal
    var bestA     = -1
    var bestP     = -1
    var bestQ     = -1
    var bestSlot  = slots.size
    var bestScore = Int.MaxValue
    // penalized
    var bestAPenalized     = -1
    var bestPPenalized     = -1
    var bestQPenalized     = -1
    var bestSlotPenalized  = slots.size
    var bestScorePenalized = Int.MaxValue
    // other auxliaries
    var score              = 0
    var currentParallelism = 0
    // var bestSlot = -1
    val lastSlot = recomputeMethods.recomputeLowerestDecidedSlot()
    // println(s"deciding with last slot $lastSlot")
    // quick guard for errors and end of schedule
    if (lastSlot >= slots.size - 1) return null
    val earliestOpen = lastSlot + 1
    var d            = pool.getE()
    if (d == null) d = IntDecision(pool)
    // count += 1
    // println(s"deciding at $earliestOpen")
    // slotsPrettyPrint()
    // recomputeFiringVectors()
    // recomputeTokens()
    // recomputeInvThroughput()
    // for (a <- actors) accumFirings(a) = 0
    // for (slot <- slots; if bestSlot < 0) {
    // accumulate the firings vectors
    // accumFirings += firingVector(lastSlot)
    wfor(0, it => it < earliestOpen + 1 && it <= bestSlot, _ + 1) { s =>
      wfor(0, _ < schedulers.size, _ + 1) { p =>
        wfor(0, _ < actors.size, _ + 1) { a =>
          if (firingsInSlots(a)(p)(s).getUB() > 0 && !firingsInSlots(a)(p)(s).isInstantiated()) {
            // compute ComRank
            // currentParallelism = 0
            // if (s > 0) {
            //   wfor(0, _ < actors.size, _ + 1) { aOther =>
            //     if (a != aOther) {
            //       wfor(0, _ < schedulers.size, _ + 1) { pOther =>
            //         if (p != pOther) {
            //           wfor(0, _ < s, _ + 1) { sBefore =>
            //             if (
            //               followsMatrix(aOther, a) && firingsInSlots(aOther)(
            //                 pOther
            //               )(sBefore).getLB() > 0
            //             ) {
            //               currentParallelism += 1
            //             }
            //           }
            //         }
            //       }
            //     }

            //   }
            // }
            // compute the best slot otherwise
            wfor(firingsInSlots(a)(p)(s).getUB(), it => it > 0 && it >= bestQ, _ - 1) { q =>
              // currentComRank = if (currentComRank <= communicationLimiter) then 0 else currentComRank - communicationLimiter
              // chains of lexographic greedy objectives
              score = (invThroughputs(p).getLB() + actorDuration(a)(p) * q)
              if (score < bestScore) {
                if (!canBuildSelfLoop(a)(p)(s)) {
                  bestA = a
                  bestP = p
                  bestQ = q
                  bestSlot = s
                  bestScore = score
                }
                bestAPenalized = a
                bestPPenalized = p
                bestQPenalized = q
                bestSlotPenalized = s
                bestScorePenalized = score
              }
            }
          }
        }
      }
    }
    // }
    // slotsPrettyPrint()
    // recomputeMethods.slotsPrettyPrint()
    // println(invThroughputs.map(_.getLB()).mkString(", "))
    // println("best:  " + (bestA, bestP, bestSlot, bestQ, bestScore))
    // println(
    //   "bestPenalized:  " + (bestAPenalized, bestPPenalized, bestSlotPenalized, bestQPenalized, bestScorePenalized)
    // )
    if (bestQ > 0) {
      // println("adding it!")
      d.set(firingsInSlots(bestA)(bestP)(bestSlot), bestQ, DecisionOperatorFactory.makeIntEq())
      d
    } else if (bestQPenalized > 0) {
      d.set(
        firingsInSlots(bestAPenalized)(bestPPenalized)(bestSlotPenalized),
        bestQPenalized,
        DecisionOperatorFactory.makeIntEq()
      )
      d
      // println("returning null")
    } else {
      null
    }
  }

  def onSolution(): Unit = {
    schedulers = Random.shuffle(schedulers).toArray
    actors = Random.shuffle(actors).toArray
    channels = Random.shuffle(channels).toArray
    // parallelism = Math.min(parallelism + 1, actors.size * schedulers.size)
  }

}