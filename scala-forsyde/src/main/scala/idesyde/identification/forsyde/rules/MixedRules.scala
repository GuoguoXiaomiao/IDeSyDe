package idesyde.identification.forsyde.rules

import idesyde.identification.DesignModel
import idesyde.identification.DecisionModel
import idesyde.identification.forsyde.ForSyDeDesignModel
import idesyde.identification.common.models.mixed.PeriodicWorkloadToPartitionedSharedMultiCore
import idesyde.identification.common.models.mixed.SDFToTiledMultiCore
import forsyde.io.java.core.ForSyDeSystemGraph
import forsyde.io.java.typed.viewers.decision.results.AnalysedGenericProcessingModule
import forsyde.io.java.typed.viewers.decision.Scheduled
import forsyde.io.java.typed.viewers.visualization.GreyBox
import forsyde.io.java.typed.viewers.visualization.Visualizable
import forsyde.io.java.typed.viewers.platform.runtime.AbstractScheduler
import forsyde.io.java.typed.viewers.decision.MemoryMapped
import forsyde.io.java.typed.viewers.platform.GenericMemoryModule
import idesyde.identification.common.models.workload.CommunicatingExtendedDependenciesPeriodicWorkload
import idesyde.identification.common.models.platform.PartitionedSharedMemoryMultiCore
import idesyde.identification.forsyde.ForSyDeIdentificationUtils
import forsyde.io.java.typed.viewers.nonfunctional.UtilizationBoundedProcessingElem
import spire.math.Rational

object MixedRules {

  def integratePeriodicWorkloadToPartitionedSharedMultiCore(
      designModel: DesignModel,
      decisionModel: DecisionModel
  ): Option[? <: DesignModel] = {
    designModel match {
      case ForSyDeDesignModel(forSyDeSystemGraph) => {
        decisionModel match {
          case dse: PeriodicWorkloadToPartitionedSharedMultiCore => {
            val rebuilt = ForSyDeSystemGraph().merge(forSyDeSystemGraph)
            for (
              (taskId, schedId) <- dse.processSchedulings;
              // ok for now because it is a 1-to-many situation wit the current Decision Models (2023-01-16)
              // TODO: fix it to be stable later
              task  = rebuilt.vertexSet().stream().filter(v => taskId.contains(v.getIdentifier())).findAny().get();
              sched = rebuilt.queryVertex(schedId).get()
            ) {
              AbstractScheduler
                .safeCast(sched)
                .ifPresent(scheduler => {
                  Scheduled.enforce(task).insertSchedulersPort(rebuilt, scheduler)
                  GreyBox.enforce(sched).insertContainedPort(rebuilt, Visualizable.enforce(task))
                })
            }
            for (
              (taskId, memId) <- dse.processMappings;
              // ok for now because it is a 1-to-many situation wit the current Decision Models (2023-01-16)
              // TODO: fix it to be stable later
              task  = rebuilt.vertexSet().stream().filter(v => taskId.contains(v.getIdentifier())).findAny().get();
              mem  = rebuilt.queryVertex(memId).get()
            ) {
              GenericMemoryModule
                .safeCast(mem)
                .ifPresent(memory =>
                  MemoryMapped.enforce(task).insertMappingHostsPort(rebuilt, memory)
                )
            }
            for (
              (channelId, memId) <- dse.channelMappings;
              // ok for now because it is a 1-to-many situation wit the current Decision Models (2023-01-16)
              // TODO: fix it to be stable later
              channel = rebuilt.vertexSet().stream().filter(v => channelId.contains(v.getIdentifier())).findAny().get();
              mem     = rebuilt.queryVertex(memId).get()
            ) {
              GenericMemoryModule
                .safeCast(mem)
                .ifPresent(memory =>
                  MemoryMapped.enforce(channel).insertMappingHostsPort(rebuilt, memory)
                )
            }
            Some(ForSyDeDesignModel(rebuilt))
          }
          case _ => Option.empty
        }
      }
      case _ => Option.empty
    }
  }

  def integrateSDFToTiledMultiCore(
      designModel: DesignModel,
      decisionModel: DecisionModel
  ): Option[? <: DesignModel] = {
    designModel match {
      case ForSyDeDesignModel(forSyDeSystemGraph) => {
        val newModel = ForSyDeSystemGraph().merge(forSyDeSystemGraph)
        decisionModel match {
          case dse: SDFToTiledMultiCore => {
            // first, we take care of the process mappings
            for (
              (mem, i) <- dse.processMappings.zipWithIndex;
              actorId   = dse.sdfApplications.actorsIdentifiers(i);
              memIdx    = dse.platform.hardware.memories.indexOf(mem);
              proc      = dse.platform.hardware.processors(memIdx);
              scheduler = dse.platform.runtimes.schedulers(memIdx)
            ) {
              newModel
                .queryVertex(actorId)
                .ifPresent(actor => {
                  newModel
                    .queryVertex(mem)
                    .ifPresent(m => {
                      val v = MemoryMapped.enforce(actor)
                      v.setMappingHostsPort(
                        newModel,
                        java.util.Set.of(GenericMemoryModule.enforce(m))
                      )
                    })
                  newModel
                    .queryVertex(scheduler)
                    .ifPresent(s => {
                      val v = Scheduled.enforce(actor)
                      v.setSchedulersPort(newModel, java.util.Set.of(AbstractScheduler.enforce(s)))
                    })
                })
            }
            Some(ForSyDeDesignModel(newModel))
          }
          case _ => Option.empty
        }
      }
      case _ => Option.empty
    }
  }

  def identPeriodicWorkloadToPartitionedSharedMultiCoreWithUtilization(
      models: Set[DesignModel],
      identified: Set[DecisionModel]
  ): Set[PeriodicWorkloadToPartitionedSharedMultiCore] = {
    ForSyDeIdentificationUtils.toForSyDe(models) { model => 
      val app = identified
        .filter(_.isInstanceOf[CommunicatingExtendedDependenciesPeriodicWorkload])
        .map(_.asInstanceOf[CommunicatingExtendedDependenciesPeriodicWorkload])
      val plat = identified
        .filter(_.isInstanceOf[PartitionedSharedMemoryMultiCore])
        .map(_.asInstanceOf[PartitionedSharedMemoryMultiCore])
      // if ((runtimes.isDefined && plat.isEmpty) || (runtimes.isEmpty && plat.isDefined))
      app.flatMap(a =>
        plat.map(p =>
          PeriodicWorkloadToPartitionedSharedMultiCore(
            workload = a,
            platform = p,
            processMappings = Vector.empty,
            processSchedulings = Vector.empty,
            channelMappings = Vector.empty,
            channelSlotAllocations = Map(),
            maxUtilizations = (for (
              pe <- p.hardware.processingElems; 
              peVertex = model.queryVertex(pe); 
              if peVertex.isPresent() && UtilizationBoundedProcessingElem.conforms(peVertex.get());
              utilVertex = UtilizationBoundedProcessingElem.safeCast(peVertex.get()).get()
            ) yield pe -> Rational(utilVertex.getMaxUtilizationNumerator(), utilVertex.getMaxUtilizationDenominator())).toMap
          )
        )
      )  
    }
  }
}