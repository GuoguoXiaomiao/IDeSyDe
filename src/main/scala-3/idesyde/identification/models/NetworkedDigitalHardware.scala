package idesyde.identification.models

import idesyde.identification.DecisionModel
import forsyde.io.java.core.Vertex
import forsyde.io.java.typed.viewers.{
  AbstractDigitalModule,
  GenericDigitalInterconnect,
  GenericDigitalStorage,
  GenericProcessingModule
}
import org.jgrapht.graph.{DefaultEdge, DirectedPseudograph, SimpleDirectedGraph}
import forsyde.io.java.core.ForSyDeModel
import forsyde.io.java.core.Edge
import forsyde.io.java.typed.viewers.RoundRobinInterconnect
import org.apache.commons.math3.fraction.Fraction

// type GenericPlatformElement = GenericProcessingModule | GenericDigitalInterconnect | GenericDigitalStorage

final case class NetworkedDigitalHardware(
    val processingElems: Set[GenericProcessingModule],
    val communicationElems: Set[GenericDigitalInterconnect],
    val storageElems: Set[GenericDigitalStorage],
    val links: Set[(AbstractDigitalModule, AbstractDigitalModule)],
    val paths: Map[(AbstractDigitalModule, AbstractDigitalModule), Seq[GenericDigitalInterconnect]]
) extends SimpleDirectedGraph[AbstractDigitalModule, DefaultEdge](classOf[DefaultEdge])
    with DecisionModel {

  for (pe <- processingElems) addVertex(pe)
  for (ce <- communicationElems) addVertex(ce)
  for (me <- storageElems) addVertex(me)
  // TODO: error here at creation
  for ((src, dst) <- links) addEdge(src, dst)

  def coveredVertexes = {
    for (p <- processingElems) yield p.getViewedVertex
    for (c <- communicationElems) yield c.getViewedVertex
    for (s <- storageElems) yield s.getViewedVertex
  }

  def coveredEdges = Set.empty

  def allocatedCommWeights: Map[(GenericDigitalInterconnect, GenericProcessingModule), Fraction] =
    (for (
      ce <- communicationElems;
      pe <- processingElems
    ) yield ce match {
      case rr: RoundRobinInterconnect =>
        (rr, pe) -> Fraction(
          rr.getAllocatedWeights.getOrDefault(pe.getIdentifier, 0),
          rr.getTotalWeights
        )
      case _ => (ce, pe) -> Fraction(0)
    }).toMap

  def bandWidthBitPerSec: Map[(GenericDigitalInterconnect, GenericProcessingModule), Long] =
    allocatedCommWeights.map((ce2pe, frac) => {
      val ce = ce2pe._1
      // TODO this computation might not be numerically stable for large numbers. to double check later.
      ce2pe -> frac
        .multiply(
          ce.getMaxFlitSizeInBits.toInt * ce.getMaxConcurrentFlits * ce.getNominalFrequencyInHertz.toInt
        )
        .longValue
    })

}
