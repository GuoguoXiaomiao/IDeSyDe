import forsyde.io.java.drivers.ForSyDeModelHandler
import idesyde.identification.api.Identification
class IdentificationTest {
  
    def testeasy() = {
        val model = ForSyDeModelHandler().loadModel("FlightInformationFunctionReactor.forxml")
        val dm = Identification.identifyDecisionModels(model)
    }
}
