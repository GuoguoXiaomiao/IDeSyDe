package idesyde.identification.rules

import idesyde.identification.IdentificationRule
import idesyde.identification.models.reactor.ReactorMinusAppMapAndSched
import forsyde.io.java.core.ForSyDeSystemGraph
import idesyde.identification.DecisionModel
import idesyde.identification.models.reactor.ReactionJob
import forsyde.io.java.typed.viewers.GenericProcessingModule
import forsyde.io.java.typed.viewers.ProfiledFunction
import forsyde.io.java.typed.viewers.ProfiledProcessingModule
import org.apache.commons.math3.fraction.BigFraction

import collection.JavaConverters.*
import idesyde.identification.models.SchedulableNetworkedDigHW
import idesyde.identification.models.reactor.ReactorMinusApplication
import forsyde.io.java.typed.viewers.LinguaFrancaReaction

final case class ReactorMinusAppDSEIdentRule() extends IdentificationRule:

  override def identify(
      model: ForSyDeSystemGraph,
      identified: Set[DecisionModel]
  ): (Boolean, Option[DecisionModel]) =
    val reactorMinusOpt =
      identified
        .find(_.isInstanceOf[ReactorMinusApplication])
        .map(_.asInstanceOf[ReactorMinusApplication])
    val schedulablePlatformOpt = identified
      .find(_.isInstanceOf[SchedulableNetworkedDigHW])
      .map(_.asInstanceOf[SchedulableNetworkedDigHW])
    if (reactorMinusOpt.isDefined && schedulablePlatformOpt.isDefined) {
      val reactorMinus                   = reactorMinusOpt.get
      val schedulablePlatform            = schedulablePlatformOpt.get
      val decisionModel = ReactorMinusAppMapAndSched(
        reactorMinus = reactorMinus,
        platform = schedulablePlatform,
        wcetFunction = computeWCETFunction(model, reactorMinus.reactions, schedulablePlatform.hardware.processingElems),
        utilityFunction = computeUtilityFunction(model, reactorMinus.reactions, schedulablePlatform.hardware.processingElems, reactorMinus.hyperPeriod)
      )
      scribe.debug(s"Identified conformin Reactor- DSE problem")
      (true, Option(decisionModel))
    } else if (ReactorMinusAppDSEIdentRule.canIdentify(model, identified))
      (false, Option.empty)
    else
      (true, Option.empty)
  end identify

  def computeWCETFunction(model: ForSyDeSystemGraph,
      reactions: Set[LinguaFrancaReaction],
      procElems: Set[GenericProcessingModule]
  ): Map[(LinguaFrancaReaction, GenericProcessingModule), BigFraction] =
    val iter = for (
      r  <- reactions;
      pe <- procElems;
      (provisionName, provisionSet) <- ProfiledProcessingModule
        .safeCast(pe)
        .map(pe => pe.getProvisions.asScala.toMap)
        .orElse(Map.empty);
      (requirementName, requirementSet) <- r.getImplementationPort(model)
          .flatMap(ProfiledFunction.safeCast(_))
          .map(f => f.getRequirements.asScala.toMap)
          .orElse(Map.empty);
      if provisionSet.keySet.equals(requirementSet.keySet)
    )
      yield (r, pe) -> BigFraction(
        provisionSet.asScala.map(op => op._2 * requirementSet.get(op._1)).sum[Long],
        pe.getNominalFrequencyInHertz
      )
    iter.toMap

  def computeUtilityFunction(model: ForSyDeSystemGraph,
      reactions: Set[LinguaFrancaReaction],
      procElems: Set[GenericProcessingModule],
      hyperPeriod: BigFraction
  ): Map[(LinguaFrancaReaction, GenericProcessingModule), BigFraction] =
    val wcetFunction = computeWCETFunction(model, reactions, procElems)
    val iter = for (
      r  <- reactions;
      pe <- procElems;
      wcet = wcetFunction.get((r, pe))
      if wcet.isDefined
    )
      yield (r, pe) ->
        wcet.get.divide(hyperPeriod)
    iter.toMap

end ReactorMinusAppDSEIdentRule

object ReactorMinusAppDSEIdentRule:

  def canIdentify(model: ForSyDeSystemGraph, identified: Set[DecisionModel]) =
    ReactorMinusIdentificationRule.canIdentify(model, identified) &&
      SchedulableNetDigHWIdentRule.canIdentify(model, identified)

end ReactorMinusAppDSEIdentRule