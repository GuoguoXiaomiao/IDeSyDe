package idesyde.identification.models.sdf

import scala.jdk.CollectionConverters.*

import forsyde.io.java.core.Vertex
import idesyde.identification.ForSyDeDecisionModel
import forsyde.io.java.typed.viewers.moc.sdf.SDFActor
import forsyde.io.java.typed.viewers.moc.sdf.SDFChannel
import forsyde.io.java.typed.viewers.moc.sdf.SDFElem
import org.apache.commons.math3.linear.Array2DRowFieldMatrix
import org.apache.commons.math3.fraction.FractionField
import org.apache.commons.math3.fraction.Fraction
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.SingularValueDecomposition
import forsyde.io.java.core.ForSyDeSystemGraph
import org.jgrapht.Graph
import org.jgrapht.graph.WeightedPseudograph
import org.jgrapht.graph.AsWeightedGraph
import idesyde.utils.SDFUtils
import org.apache.commons.math3.fraction.BigFraction
import idesyde.identification.models.workload.ParametricRateDataflowWorkloadMixin
import org.jgrapht.graph.DefaultDirectedGraph
import org.jgrapht.graph.DefaultEdge
import forsyde.io.java.typed.viewers.impl.Executable
import idesyde.identification.models.workload.InstrumentedWorkloadMixin
import forsyde.io.java.typed.viewers.impl.InstrumentedExecutable
import scala.collection.mutable
import forsyde.io.java.typed.viewers.impl.TokenizableDataBlock

final case class SDFApplication(
    val actors: Array[SDFActor],
    val channels: Array[SDFChannel],
    val topology: Graph[SDFActor | SDFChannel, Int],
    actorFuncs: Array[Array[Executable]] = Array.empty
)(using Integral[BigFraction])
    extends ForSyDeDecisionModel
    with ParametricRateDataflowWorkloadMixin
    with InstrumentedWorkloadMixin {

  val actorFunctions =
    if (actorFuncs.isEmpty) then Array.fill(actors.size)(Array.empty[Executable]) else actorFuncs

  // def dominatesSdf(other: SDFApplication) = repetitionVector.size >= other.repetitionVector.size
  val coveredVertexes =
    actors.map(_.getViewedVertex) ++
      channels.map(_.getViewedVertex)

  def actorsSet: Array[Int]   = (0 until actors.size).toArray
  def channelsSet: Array[Int] = (actors.size until (actors.size + channels.size)).toArray

  val initialTokens: Array[Int] = channels.map(_.getNumOfInitialTokens)

  def isSelfConcurrent(actor: Int): Boolean = {
    val a = actors(actor)
    channels.exists(c => topology.containsEdge(a, c) && topology.containsEdge(c, a))
  }

  lazy val dataflowGraphs = {
    val g = DefaultDirectedGraph.createBuilder[Int, Int](() => 0)
    actors.zipWithIndex.foreach((a, i) => {
      channels.zipWithIndex.foreach((c, prej) => {
        val j = channelsSet(prej)
        topology.getAllEdges(a, c).forEach(p => g.addEdge(i, j, p))
        topology.getAllEdges(c, a).forEach(p => g.addEdge(j, i, p))
      })
    })
    Array(g.buildAsUnmodifiable)
  }

  val configurations = {
    val g = DefaultDirectedGraph.createBuilder[Int, DefaultEdge](() => DefaultEdge())
    g.addEdge(0, 0)
    g.buildAsUnmodifiable
  }

  def processComputationalNeeds: Array[Map[String, Map[String, Long]]] =
    actorFunctions.map(actorFuncs => {
      // we do it mutable for simplicity...
      // the performance hit should not be a concern now, for super big instances, this can be reviewed
      var mutMap = mutable.Map[String, mutable.Map[String, Long]]()
      actorFuncs.foreach(func => {
        InstrumentedExecutable
          .safeCast(func)
          .ifPresent(ifunc => {
            // now they have to be aggregated
            ifunc
              .getOperationRequirements()
              .entrySet()
              .forEach(e => {
                val innerMap = e.getValue().asScala.map((k, v) => k -> v.asInstanceOf[Long])
                // first the intersection parts
                mutMap(e.getKey()) = mutMap
                  .getOrElse(e.getKey(), innerMap)
                  .map((k, v) => k -> (v + innerMap.getOrElse(k, 0L)))
                // now the parts only the other map has
                (innerMap.keySet -- mutMap(e.getKey()).keySet)
                  .map(k => mutMap(e.getKey())(k) = innerMap(k))
              })
          })
      })
      mutMap.map((k, v) => k -> v.toMap).toMap
    })

  def processSizes: Array[Long] = actors.zipWithIndex.map((a, i) =>
    InstrumentedExecutable.safeCast(a).map(_.getSizeInBits().asInstanceOf[Long]).orElse(0L) +
      actorFunctions
        .flatMap(fs =>
          fs.map(
            InstrumentedExecutable.safeCast(_).map(_.getSizeInBits().asInstanceOf[Long]).orElse(0L)
          )
        )
        .sum
  )

  def messagesMaxSizes: Array[Long] = channels.zipWithIndex.map((c, i) =>
    pessimisticTokensPerChannel(i) * TokenizableDataBlock
      .safeCast(c)
      .map(d => d.getTokenSizeInBits())
      .orElse(0L)
  )

  override val uniqueIdentifier = "SDFApplication"

}
