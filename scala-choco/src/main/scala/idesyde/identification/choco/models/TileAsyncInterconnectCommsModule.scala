package idesyde.identification.choco.models

import idesyde.identification.choco.interfaces.ChocoModelMixin
import idesyde.identification.models.platform.TiledMultiCorePlatformMixin
import spire.algebra._
import spire.math._
import spire.implicits._
import org.chocosolver.solver.variables.BoolVar
import org.chocosolver.solver.variables.IntVar
import org.chocosolver.solver.variables.Task
import org.chocosolver.solver.constraints.Propagator
import org.chocosolver.solver.constraints.PropagatorPriority
import org.chocosolver.util.ESat
import org.jgrapht.graph.SimpleDirectedGraph
import org.jgrapht.graph.DefaultEdge
import org.jgrapht.alg.color.LargestDegreeFirstColoring
import org.chocosolver.solver.constraints.Constraint
import idesyde.utils.CoreUtils.wfor
import org.jgrapht.graph.SimpleGraph
import idesyde.identification.choco.models.platform.ContentionFreeTiledCommunicationPropagator
import org.chocosolver.solver.Model

class TileAsyncInterconnectCommsModule(
    val chocoModel: Model,
    val procElems: Array[Int],
    val commElems: Array[Int],
    val messages: Array[Int],
    val messageTravelTimePerVirtualChannel: Array[Array[Int]],
    val numVirtualChannels: Array[Int],
    val commElemsPaths: Array[Array[Array[Int]]],
    val commElemsMustShareChannel: Array[Array[Boolean]],
    val messagesMapping: Array[IntVar]
) extends ChocoModelMixin() {

  private val numProcElems = procElems.size
  private val numCommElems = commElems.size
  private val numMessages  = messages.size

  val virtualChannelForMessage: Array[Array[IntVar]] = messages.zipWithIndex.map((c, i) => {
    commElems.zipWithIndex.map((ce, j) => {
      chocoModel.intVar(
        s"vc($c,${j})",
        0,
        numVirtualChannels(j)
      )
    })
  })

  val messageIsCommunicated: Array[Array[Array[BoolVar]]] = messages.map(c => {
    procElems.zipWithIndex.map((src, i) => {
      procElems.zipWithIndex.map((dst, j) => {
        if (!commElemsPaths(i)(j).isEmpty)
          chocoModel.boolVar(s"send($c,${i},${j})")
        else
          chocoModel.boolVar(s"send($c,${i},${j})", false)
      })
    })
  })

  val messageTravelDuration: Array[Array[Array[IntVar]]] = messages.zipWithIndex.map((c, ci) => {
    procElems.zipWithIndex.map((src, i) => {
      procElems.zipWithIndex.map((dst, j) => {
        chocoModel.intVar(
          s"commTime(${c},${i},${j})",
          commElemsPaths(i)(j)
            .map(ce => messageTravelTimePerVirtualChannel(ci)(commElems.indexOf(ce)))
            .minOption
            .getOrElse(0),
          commElemsPaths(i)(j)
            .map(ce => messageTravelTimePerVirtualChannel(ci)(commElems.indexOf(ce)))
            .sum,
          true
        )
      })
    })
  })

  private val messagesClashAtComm: Array[Array[Array[BoolVar]]] =  messages.map(mi =>
    messages.map(mj =>
      commElems.map(c =>
        if (mi != mj) {
          chocoModel.boolVar(s"messageClash($mi,$mj,$c)")
        } else {
          chocoModel.boolVar(s"messageClash($mi,$mj,$c)", false)
        }
      )  
    )  
  )

  def postTileAsyncInterconnectComms(): Unit = {
    // first, make sure that data from different sources do not collide in any comm. elem
    // for (ce <- commElems) {
    // chocoModel.allDifferentExcept0(messages.map(m => virtualChannelForMessage(m)(ce))).post()
    // }
    for (
      src <- 0 until numProcElems;
      dst <- 0 until numProcElems;
      if src != dst;
      c  <- 0 until numMessages;
      ce <- commElemsPaths(src)(dst)
    ) {
      chocoModel.ifThen(
        messageIsCommunicated(c)(src)(dst),
        chocoModel.arithm(virtualChannelForMessage(c)(commElems.indexOf(ce)), ">", 0)
      )
      // chocoModel.ifThen(
      //   chocoModel.arithm(messageIsCommunicated(c)(src)(dst), "=", 0),
      //   chocoModel.arithm(virtualChannelForMessage(c)(commElems.indexOf(ce)), "=", 0)
      // )
    }
    // if two communicating actors are in different tiles,
    // they must communicate
    // for (
    //   srci <- procElems;
    //   srcj <- procElems;
    //   if srci != srcj;
    //   dsti <- procElems;
    //   dstj <- procElems;
    //   ce   <- commElemsPath(srci)(dsti);
    //   if commElemsPath(srcj)(dstj).contains(ce)
    // ) {
    //   // println(s"$srci -> $dsti and $srcj -> $dstj crash at $ce")
    //   for (
    //     (mi, mii) <- messages.zipWithIndex.dropRight(1);
    //     mj <- messages.drop(
    //       mii + 1
    //     ) // it might seems strange that we consider the same message, but it is required for proper allocation
    //   ) {
    //     // println(s"posting for $mi and $mj")
    //     chocoModel.ifThen(
    //       chocoModel.and(messageIsCommunicated(mi)(srci)(dsti),
    //         messageIsCommunicated(mj)(srcj)(dstj)),
    //       chocoModel.notAllEqual(virtualChannelForMessage(mi)(ce), virtualChannelForMessage(mj)(ce))
    //     )
    //   }
    // }

    // now we make sure that adjacent comm elems that must have the same channel for the same
    // message, indeed do.
    for ((ce, i) <- commElems.zipWithIndex) {
      val areEquals =
        commElems.zipWithIndex.filter((otherCe, j) =>
          ce != otherCe && commElemsMustShareChannel(i)(j)
        )
      for ((m, k) <- messages.zipWithIndex) {
        chocoModel
          .allEqual(
            ((ce, i) +: areEquals).map((c, j) =>
              virtualChannelForMessage(k)(commElems.indexOf(c))
            ): _*
          )
          .post()
      }
    }

    // and finally, we calculate the travel time, for each message, between each src and dst
    for (
      m   <- 0 until numMessages;
      src <- 0 until numProcElems;
      dst <- 0 until numProcElems;
      if src != dst && !commElemsPaths(src)(dst).isEmpty
    ) {
      // val travelDuration = chocoModel.sum(s"travelSum($m, $src, $dst)", :_*)
      chocoModel.ifThen(
        messageIsCommunicated(m)(src)(dst),
        messageTravelDuration(m)(src)(dst)
          .eq(
            commElemsPaths(src)(dst)
              .map(ce => messageTravelTimePerVirtualChannel(m)(commElems.indexOf(ce)))
              .sum
          )
          .decompose()
      )
    }

    //   chocoModel.post(
    //     new Constraint(
    //       "tile_async_vc",
    //       ContentionFreeTiledCommunicationPropagator(
    //         procElems,
    //         commElems,
    //         messages,
    //         procElems.zipWithIndex.map((src, srci) => procElems.zipWithIndex.map((dst, dsti) => commElemsPaths(srci)(dsti))),
    //         messages.zipWithIndex.map((m, mi) => commElems.zipWithIndex.map((ce, cei) => virtualChannelForMessage(mi)(cei))),
    //         messages.zipWithIndex.map((m, mi) =>
    //           procElems.zipWithIndex.map((src, srci) => procElems.zipWithIndex.map((dst, dsti) => messageIsCommunicated(mi)(srci)(dsti)))
    //         )
    //       )
    //     )
    //   )

    // now make sure no two messages can collide in the network
    for (
      mi <- 0 until numMessages - 1;
      mj <- mi + 1 until numMessages;
      // if mi != mj;
      c <- 0 until numCommElems
    ) {
      // trivial connection between variables
      chocoModel.ifThen(
        messagesClashAtComm(mi)(mj)(c),
        chocoModel.arithm(virtualChannelForMessage(mi)(c), "!=", virtualChannelForMessage(mj)(c))
      )
      // now the logics 
      val incomingMessagei = messageIsCommunicated(mi).zipWithIndex.flatMap((srcVec, src) => 
        srcVec.zipWithIndex.filter((v, dst) => src != dst && commElemsPaths(src)(dst).contains(commElems(c)))
        .map((v, _) => v)
      )
      val incomingMessagej = messageIsCommunicated(mj).zipWithIndex.flatMap((srcVec, src) => 
        srcVec.zipWithIndex.filter((v, dst) => src != dst && commElemsPaths(src)(dst).contains(commElems(c)))
        .map((v, _) => v)
      )
      val sumIncomingMessagei = chocoModel.or(incomingMessagei:_*)
      val sumIncomingMessagej = chocoModel.or(incomingMessagej:_*)
      val clashing = chocoModel.and(chocoModel.arithm(messagesMapping(mi), "!=", messagesMapping(mj)), sumIncomingMessagei, sumIncomingMessagej)
      chocoModel.ifOnlyIf(chocoModel.arithm(messagesClashAtComm(mi)(mj)(c), "=", 1), clashing)
    }
    // wfor(0, _ < messages.size - 1, _ + 1) { mi =>
    //   wfor(0, _ < procElems.size, _ + 1) { srci =>
    //     wfor(0, _ < procElems.size, _ + 1) { dsti =>
    //       if (srci != dsti) {
    //         // this extra line is not fully part of the reference, but it is here for performance reasons,
    //         // i.e. to avoid duplication of looping
    //         wfor(mi + 1, _ < messages.size, _ + 1) { mj =>
    //           wfor(0, _ < procElems.size, _ + 1) { srcj =>
    //             wfor(0, _ < procElems.size, _ + 1) { dstj =>
    //               if (srci != srcj) {
    //                 wfor(0, _ < commElemsPaths(srci)(dsti).size, _ + 1) { ceIdx =>
    //                   if (commElemsPaths(srcj)(dstj).contains(commElemsPaths(srci)(dsti)(ceIdx))) {
    //                     // there can be a conflict
    //                     chocoModel.ifThen(
    //                       messageIsCommunicated(mi)(srci)(dsti)
    //                         .and(messageIsCommunicated(mj)(srcj)(dstj))
    //                         .decompose(),
    //                       virtualChannelForMessage(mi)(ceIdx)
    //                         .ne(virtualChannelForMessage(mj)(ceIdx))
    //                         .decompose()
    //                     )
    //                     // if (!interferenceGraphPerComm(ceIdx).containsVertex(messages(mi))) then
    //                     //   interferenceGraphPerComm(ceIdx).addVertex(messages(mi))
    //                     // if (!interferenceGraphPerComm(ceIdx).containsVertex(messages(mj))) then
    //                     //   interferenceGraphPerComm(ceIdx).addVertex(messages(mj))
    //                     // interferenceGraphPerComm(ceIdx).addEdge(messages(mi), messages(mj))
    //                   }
    //                 }
    //               }
    //             }
    //           }
    //         }
    //       }
    //     }
    //   }
    // }
  }

}