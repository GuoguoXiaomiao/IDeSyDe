package idesyde.cli

import java.util.concurrent.Callable
import picocli.CommandLine.*
import java.io.File
import forsyde.io.java.core.ForSyDeModel
import forsyde.io.java.drivers.ForSyDeModelHandler
import idesyde.identification.api.Identification
import scribe.Level

@Command(
  name = "idesyde",
  mixinStandardHelpOptions = true,
  description = Array("""
  ___  ___        ___        ___
 |_ _||   \  ___ / __| _  _ |   \  ___ 
  | | | |) |/ -_)\__ \| || || |) |/ -_)
 |___||___/ \___||___/ \_, ||___/ \___|
                       |__/

Automated Identification and Exploration of Design Spaces in ForSyDe
""")
)
class IDeSyDeCLI extends Callable[Int] {

  @Parameters(
    paramLabel = "Input Model",
    description = Array("input models to perform analysis")
  )
  var inputModels: Array[File] = Array()

  @Option(
    names = Array("-o", "--output"),
    description = Array("output model to output after analysis")
  )
  var outputModel: File = File("forsyde-output.forxml")

  @Option(
    names = Array("-v", "--verbosity"),
    description = Array("set the verbosity level for logging")
  )
  var verbosityLevel: String = "INFO"

  def call(): Int = {
    setLoggingLevel(Level.get(verbosityLevel).getOrElse(Level.Info))
    val validInputs =
      inputModels.filter(f => f.getName.endsWith("forsyde.xml") || f.getName.endsWith("forxml"))
    if (validInputs.isEmpty) {
      println(
        "At least one input model '.forsyde.xml' | '.forxml' is necessary"
      )
    } else {
      scribe.info("Reading and merging input models.")
      val models = validInputs.map(i => ForSyDeModelHandler().loadModel(i))
      val mergedModel = {
        val mhead = models.head
        models.tail.foreach(mhead.mergeInPlace(_))
        mhead
      }
      val identified = Identification.identifyDecisionModels(mergedModel)
      scribe.info(s"Identification finished with ${identified.size} decision models.")
    }
    0
  }

  def setLoggingLevel(loggingLevel: Level) = {
    scribe.info(s"Set logging levels to ${loggingLevel.name}.")
    scribe.Logger.root
      .clearHandlers()
      .clearModifiers()
      .withHandler(minimumLevel = Some(loggingLevel))
      .replace()
  }
}
