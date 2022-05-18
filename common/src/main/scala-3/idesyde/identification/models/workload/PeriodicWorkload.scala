package idesyde.identification.models.workload

import forsyde.io.java.core.Vertex
import idesyde.identification.ForSyDeDecisionModel
import org.jgrapht.opt.graph.sparse.SparseIntDirectedGraph

import math.Fractional.Implicits.infixFractionalOps
import org.jgrapht.Graph
import org.jgrapht.graph.DefaultEdge
import forsyde.io.java.typed.viewers.execution.Upsample
import forsyde.io.java.typed.viewers.execution.Downsample

/** Interface that describes a periodic workload model, also commonly known in the real time
  * academic community as "periodic task model". This one in particular closely follows the
  * definitions in:
  *
  * Scheduling Dependent Periodic Tasks Without Synchronization Mechanisms, Julien Forget Frédéric
  * Boniol, E. G. D. L. C. 2010 16th IEEE Real-Time and Embedded Technology and Applications
  * Symposium, 2010, 301-310
  *
  * @tparam TaskT
  *   The type representing each task.
  * @tparam TimeT
  *   The type that represents a time tag.
  */
trait PeriodicWorkload[TaskT, MQueueT, TimeT]()(using fracT: Fractional[TimeT])
    extends ForSyDeDecisionModel:

  def periodicTasks: Array[TaskT]
  def offsets: Array[TimeT]
  def periods: Array[TimeT]
  def relativeDeadlines: Array[TimeT]
  def taskSizes: Array[Long]
  // def instancePreceeds(src: TaskT)(dst: TaskT)(srcI: Int)(dstI: Int): Boolean
  def messageQueues: Array[MQueueT]
  def messageQueuesSizes: Array[Long]

  /** The edges of the instance control flow graph detail if a instance T_i,k
   * shoud be preceeded of an instance T_j,l.def 
   * 
   * In other words, it is a precedence graph at the instance (sometimes called jobs)
   * level.
   */
  def affineRelationsGraph: Graph[TaskT, Seq[(Int, Int, Int, Int)]]

  /** The edges of the communication graph should have numbers describing how much
   * data is transferred from tasks to message queues.
   */
  def communicationGraph: Graph[TaskT | MQueueT, DefaultEdge]

  /** a function that returns the LCM upper bound of two time values
    */
  def computeLCM(t1: TimeT, t2: TimeT): TimeT

  // the following implementations is not efficient. But generic.
  // TODO: finding a way that is both eficient and generic
  def instancesReleases(tidx: Int)(int: Int): TimeT =
    (1 until int).map(_ => periods(tidx)).sum + offsets(tidx) // *(fracT.one * int)
  def instancesDeadlines(tidx: Int)(int: Int): TimeT =
    (1 until int).map(_ => relativeDeadlines(tidx)).sum + offsets(tidx) //*(fracT.one * int)

  lazy val hyperPeriod: TimeT = periods.reduce((t1, t2) => computeLCM(t1, t2))
  lazy val tasksNumInstances: Array[Int] =
    (0 until periodicTasks.length).map(i => hyperPeriod / periods(i)).map(_.toInt).toArray

  def maximalInterference(srcTask: TaskT)(dstTask: TaskT)(using num: Numeric[TimeT]): Int =
    val src = periodicTasks.indexOf(srcTask)
    val dst = periodicTasks.indexOf(dstTask)
    (0 until tasksNumInstances(dst))
      .map(dstIdx => {
        (0 until tasksNumInstances(src))
          // .filterNot(srcIdx =>
          //   instancePreceeds(src)(dst)(srcIdx)(dstIdx)
          // )
          .count(srcIdx => {
            // check intersection by comparing the endpoints
            (
              num.compare(
                instancesReleases(src)(srcIdx),
                instancesReleases(dst)(dstIdx)
              ) <= 0 &&
              num.compare(
                instancesReleases(dst)(dstIdx),
                instancesDeadlines(src)(srcIdx)
              ) <= 0
            )
            ||
            (
              num.compare(
                instancesReleases(dst)(dstIdx),
                instancesReleases(src)(srcIdx)
              ) <= 0 &&
              num.compare(
                instancesReleases(src)(srcIdx),
                instancesDeadlines(dst)(dstIdx)
              ) <= 0
            )
          })
      })
      .max

end PeriodicWorkload
